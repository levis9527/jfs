// Package index implements the volume sidecar index used for fast recovery.
//
// index record (16 bytes, big-endian):
//
//	| key(int64) | offset(uint32) | size(int32) |
package index

import (
	"encoding/binary"
	"errors"
	"io"
	"os"
	"sync"
)

const RecordSize = 16

var ErrSize = errors.New("index: invalid size")

// Record is one index entry mapping key -> aligned offset + data size.
type Record struct {
	Key    int64
	Offset uint32
	Size   int32
}

func (r *Record) Encode(buf []byte) {
	binary.BigEndian.PutUint64(buf[0:8], uint64(r.Key))
	binary.BigEndian.PutUint32(buf[8:12], r.Offset)
	binary.BigEndian.PutUint32(buf[12:16], uint32(r.Size))
}

func (r *Record) Decode(buf []byte) error {
	if len(buf) < RecordSize {
		return ErrSize
	}
	r.Key = int64(binary.BigEndian.Uint64(buf[0:8]))
	r.Offset = binary.BigEndian.Uint32(buf[8:12])
	r.Size = int32(binary.BigEndian.Uint32(buf[12:16]))
	if r.Size < 0 {
		return ErrSize
	}
	return nil
}

// Indexer appends index records and can rebuild an in-memory map.
type Indexer struct {
	mu   sync.Mutex
	file *os.File
	path string
}

// Open creates or opens an index file.
func Open(path string) (*Indexer, error) {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o664)
	if err != nil {
		return nil, err
	}
	return &Indexer{file: f, path: path}, nil
}

// Append writes one index record synchronously.
func (ix *Indexer) Append(rec Record) error {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	var buf [RecordSize]byte
	rec.Encode(buf[:])
	_, err := ix.file.Write(buf[:])
	if err != nil {
		return err
	}
	return ix.file.Sync()
}

// Recovery loads all records; later keys override earlier ones (update semantics).
func (ix *Indexer) Recovery() (map[int64]Record, error) {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	if _, err := ix.file.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	out := make(map[int64]Record)
	buf := make([]byte, RecordSize)
	for {
		n, err := io.ReadFull(ix.file, buf)
		if err == io.EOF || err == io.ErrUnexpectedEOF {
			if n == 0 || err == io.EOF {
				break
			}
			return nil, err
		}
		if err != nil {
			return nil, err
		}
		var rec Record
		if err := rec.Decode(buf); err != nil {
			return nil, err
		}
		out[rec.Key] = rec
	}
	return out, nil
}

// Close closes the index file.
func (ix *Indexer) Close() error {
	ix.mu.Lock()
	defer ix.mu.Unlock()
	if ix.file == nil {
		return nil
	}
	err := ix.file.Close()
	ix.file = nil
	return err
}

// Path returns the index file path.
func (ix *Indexer) Path() string { return ix.path }
