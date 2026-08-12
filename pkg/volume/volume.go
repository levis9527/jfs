// Package volume implements a Haystack volume: superblock + index + memory map.
package volume

import (
	"bufio"
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sync"

	"github.com/levis9527/jfs/pkg/index"
	"github.com/levis9527/jfs/pkg/meta"
	"github.com/levis9527/jfs/pkg/needle"
)

var (
	ErrNotFound  = errors.New("volume: needle not found")
	ErrReadOnly  = errors.New("volume: read only")
	ErrFull      = errors.New("volume: full")
	ErrBadCookie = needle.ErrCookie
)

// Volume is one logical disk volume (superblock + index).
type Volume struct {
	mu       sync.RWMutex
	ID       int32
	dataPath string
	idxPath  string
	data     *os.File
	indexer  *index.Indexer
	needles  map[int64]index.Record // key -> latest location
	offset   int64                  // next append byte offset
	maxSize  int64
	readOnly bool
	closed   bool
}

// Options configures volume creation.
type Options struct {
	MaxSize int64 // max superblock size in bytes; 0 => 1GiB
}

// Open opens or creates volume files under dir: {id}.dat / {id}.idx
func Open(dir string, id int32, opt Options) (*Volume, error) {
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return nil, err
	}
	if opt.MaxSize <= 0 {
		opt.MaxSize = 1 << 30 // 1 GiB default for local demos
	}
	v := &Volume{
		ID:       id,
		dataPath: filepath.Join(dir, fmt.Sprintf("%d.dat", id)),
		idxPath:  filepath.Join(dir, fmt.Sprintf("%d.idx", id)),
		needles:  make(map[int64]index.Record),
		maxSize:  opt.MaxSize,
	}
	var err error
	if v.data, err = os.OpenFile(v.dataPath, os.O_RDWR|os.O_CREATE, 0o664); err != nil {
		return nil, err
	}
	if v.indexer, err = index.Open(v.idxPath); err != nil {
		_ = v.data.Close()
		return nil, err
	}
	if err = v.recover(); err != nil {
		_ = v.Close()
		return nil, err
	}
	return v, nil
}

func (v *Volume) recover() error {
	recs, err := v.indexer.Recovery()
	if err != nil {
		return err
	}
	info, err := v.data.Stat()
	if err != nil {
		return err
	}
	v.offset = info.Size()

	if len(recs) == 0 && v.offset > 0 {
		// Index missing/empty: scan superblock (slow path).
		return v.scanBlock()
	}

	// Prefer index; then replay any needles after last indexed offset.
	var lastByte int64
	for _, r := range recs {
		v.needles[r.Key] = r
		end := needle.BlockOffset(r.Offset) + int64(needle.CalcTotalSize(r.Size))
		if end > lastByte {
			lastByte = end
		}
	}
	if v.offset > lastByte {
		if err := v.replayFrom(lastByte); err != nil {
			return err
		}
	}
	return nil
}

func (v *Volume) scanBlock() error {
	return v.replayFrom(0)
}

func (v *Volume) replayFrom(from int64) error {
	if _, err := v.data.Seek(from, io.SeekStart); err != nil {
		return err
	}
	rd := bufio.NewReader(v.data)
	off := from
	for {
		hdr, err := rd.Peek(needle.HeaderSize)
		if err == io.EOF {
			break
		}
		if err != nil {
			return err
		}
		size := int32(binary.BigEndian.Uint32(hdr[17:21]))
		total := needle.CalcTotalSize(size)
		buf := make([]byte, total)
		if _, err := io.ReadFull(rd, buf); err != nil {
			if err == io.EOF || err == io.ErrUnexpectedEOF {
				// truncated tail — truncate file to last good offset
				v.offset = off
				return v.data.Truncate(off)
			}
			return err
		}
		n, err := needle.Decode(buf)
		if err != nil {
			return fmt.Errorf("volume: corrupt needle at %d: %w", off, err)
		}
		rec := index.Record{
			Key:    n.Key,
			Offset: needle.NeedleOffset(off),
			Size:   n.Size,
		}
		if n.Flag == needle.FlagDel {
			delete(v.needles, n.Key)
		} else {
			v.needles[n.Key] = rec
		}
		// backfill index for recovered needles
		if err := v.indexer.Append(rec); err != nil {
			return err
		}
		off += int64(total)
	}
	v.offset = off
	return nil
}

// Write appends a needle and updates memory + index.
func (v *Volume) Write(key int64, cookie int32, data []byte) (meta.NeedleLoc, error) {
	v.mu.Lock()
	defer v.mu.Unlock()
	if v.closed {
		return meta.NeedleLoc{}, ErrReadOnly
	}
	if v.readOnly {
		return meta.NeedleLoc{}, ErrReadOnly
	}
	buf, n, err := needle.Encode(key, cookie, data)
	if err != nil {
		return meta.NeedleLoc{}, err
	}
	if v.offset+int64(n.TotalSize) > v.maxSize {
		return meta.NeedleLoc{}, ErrFull
	}
	if _, err := v.data.WriteAt(buf, v.offset); err != nil {
		return meta.NeedleLoc{}, err
	}
	if err := v.data.Sync(); err != nil {
		return meta.NeedleLoc{}, err
	}
	rec := index.Record{
		Key:    key,
		Offset: needle.NeedleOffset(v.offset),
		Size:   n.Size,
	}
	if err := v.indexer.Append(rec); err != nil {
		return meta.NeedleLoc{}, err
	}
	v.needles[key] = rec
	v.offset += int64(n.TotalSize)
	return meta.NeedleLoc{
		Key:    key,
		Cookie: cookie,
		Vid:    v.ID,
		Offset: rec.Offset,
		Size:   rec.Size,
	}, nil
}

// Read loads needle data by key and validates cookie.
func (v *Volume) Read(key int64, cookie int32) ([]byte, error) {
	v.mu.RLock()
	rec, ok := v.needles[key]
	v.mu.RUnlock()
	if !ok {
		return nil, ErrNotFound
	}
	total := needle.CalcTotalSize(rec.Size)
	buf := make([]byte, total)
	off := needle.BlockOffset(rec.Offset)
	if _, err := v.data.ReadAt(buf, off); err != nil {
		return nil, err
	}
	n, err := needle.Decode(buf)
	if err != nil {
		return nil, err
	}
	if n.Flag == needle.FlagDel {
		v.mu.Lock()
		delete(v.needles, key)
		v.mu.Unlock()
		return nil, ErrNotFound
	}
	if n.Key != key {
		return nil, needle.ErrKey
	}
	if n.Cookie != cookie {
		return nil, ErrBadCookie
	}
	out := make([]byte, len(n.Data))
	copy(out, n.Data)
	return out, nil
}

// Delete marks a needle deleted (flag update) and drops the memory map entry.
func (v *Volume) Delete(key int64, cookie int32) error {
	v.mu.Lock()
	defer v.mu.Unlock()
	rec, ok := v.needles[key]
	if !ok {
		return ErrNotFound
	}
	// verify cookie first
	total := needle.CalcTotalSize(rec.Size)
	buf := make([]byte, total)
	off := needle.BlockOffset(rec.Offset)
	if _, err := v.data.ReadAt(buf, off); err != nil {
		return err
	}
	n, err := needle.Decode(buf)
	if err != nil {
		return err
	}
	if n.Cookie != cookie {
		return ErrBadCookie
	}
	if _, err := v.data.WriteAt([]byte{needle.FlagDel}, off+needle.FlagOffset()); err != nil {
		return err
	}
	if err := v.data.Sync(); err != nil {
		return err
	}
	delete(v.needles, key)
	return nil
}

// State returns scheduling metadata.
func (v *Volume) State() meta.VolumeState {
	v.mu.RLock()
	defer v.mu.RUnlock()
	return meta.VolumeState{
		ID:        v.ID,
		Path:      v.dataPath,
		FreeSpace: v.maxSize - v.offset,
		FileCount: len(v.needles),
		ReadOnly:  v.readOnly || v.closed,
	}
}

// SetReadOnly toggles write availability.
func (v *Volume) SetReadOnly(ro bool) {
	v.mu.Lock()
	v.readOnly = ro
	v.mu.Unlock()
}

// Close closes volume files.
func (v *Volume) Close() error {
	v.mu.Lock()
	defer v.mu.Unlock()
	v.closed = true
	var err1, err2 error
	if v.indexer != nil {
		err1 = v.indexer.Close()
	}
	if v.data != nil {
		err2 = v.data.Close()
	}
	if err1 != nil {
		return err1
	}
	return err2
}
