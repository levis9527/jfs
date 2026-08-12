// Package directory manages file metadata and write scheduling (bfs directory).
// Instead of HBase, metadata is persisted in a local JSONL + in-memory index.
package directory

import (
	"bufio"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/levis9527/jfs/pkg/idgen"
	"github.com/levis9527/jfs/pkg/meta"
	"github.com/levis9527/jfs/pkg/needle"
	"github.com/levis9527/jfs/pkg/store"
)

var (
	ErrNotFound = errors.New("directory: file not found")
	ErrExists   = errors.New("directory: file already exists")
)

// Directory owns logical file metadata and allocates keys / volumes.
type Directory struct {
	mu       sync.RWMutex
	files    map[string]*meta.FileMeta // bucket/filename -> meta
	byKey    map[int64]*meta.FileMeta
	store    *store.Store
	idgen    *idgen.Generator
	metaFile *os.File
	metaPath string
}

// Open creates a directory service backed by store + local metadata file.
func Open(metaDir string, st *store.Store, workerID int64) (*Directory, error) {
	if err := os.MkdirAll(metaDir, 0o755); err != nil {
		return nil, err
	}
	gen, err := idgen.New(workerID)
	if err != nil {
		return nil, err
	}
	path := filepath.Join(metaDir, "files.jsonl")
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o664)
	if err != nil {
		return nil, err
	}
	d := &Directory{
		files:    make(map[string]*meta.FileMeta),
		byKey:    make(map[int64]*meta.FileMeta),
		store:    st,
		idgen:    gen,
		metaFile: f,
		metaPath: path,
	}
	if err := d.load(); err != nil {
		_ = f.Close()
		return nil, err
	}
	return d, nil
}

func fileKey(bucket, filename string) string {
	return bucket + "/" + filename
}

func (d *Directory) load() error {
	sc := bufio.NewScanner(d.metaFile)
	// allow large metadata lines
	buf := make([]byte, 0, 64*1024)
	sc.Buffer(buf, 4*1024*1024)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var m meta.FileMeta
		if err := json.Unmarshal(line, &m); err != nil {
			return err
		}
		k := fileKey(m.Bucket, m.Filename)
		if m.Deleted {
			delete(d.files, k)
			delete(d.byKey, m.Key)
			continue
		}
		cp := m
		d.files[k] = &cp
		d.byKey[m.Key] = &cp
	}
	return sc.Err()
}

func (d *Directory) appendMeta(m *meta.FileMeta) error {
	b, err := json.Marshal(m)
	if err != nil {
		return err
	}
	b = append(b, '\n')
	if _, err := d.metaFile.Write(b); err != nil {
		return err
	}
	return d.metaFile.Sync()
}

// Upload allocates key/cookie/volume, writes to store, then persists metadata.
func (d *Directory) Upload(bucket, filename, mime string, data []byte) (*meta.UploadResult, error) {
	if bucket == "" || filename == "" {
		return nil, errors.New("directory: bucket and filename required")
	}
	d.mu.Lock()
	defer d.mu.Unlock()

	k := fileKey(bucket, filename)
	if _, ok := d.files[k]; ok {
		return nil, ErrExists
	}

	key, err := d.idgen.Next()
	if err != nil {
		return nil, err
	}
	cookie := idgen.NextCookie(key)
	need := int64(needle.CalcTotalSize(int32(len(data))))
	vid, err := d.store.PickVolume(need)
	if err != nil {
		return nil, err
	}
	loc, err := d.store.Write(vid, key, cookie, data)
	if err != nil {
		return nil, err
	}

	fm := &meta.FileMeta{
		Bucket:   bucket,
		Filename: filename,
		Mime:     mime,
		Key:      key,
		Cookie:   cookie,
		Vid:      loc.Vid,
		Size:     int32(len(data)),
		Created:  time.Now().Unix(),
	}
	if err := d.appendMeta(fm); err != nil {
		// best-effort: physical needle remains; metadata write failed
		return nil, err
	}
	d.files[k] = fm
	d.byKey[key] = fm

	return &meta.UploadResult{
		Bucket:   bucket,
		Filename: filename,
		Key:      key,
		Cookie:   cookie,
		Vid:      loc.Vid,
		Size:     fm.Size,
		URL:      "/" + bucket + "/" + filename,
	}, nil
}

// GetMeta resolves bucket/filename to needle location metadata.
func (d *Directory) GetMeta(bucket, filename string) (*meta.FileMeta, error) {
	d.mu.RLock()
	defer d.mu.RUnlock()
	fm, ok := d.files[fileKey(bucket, filename)]
	if !ok || fm.Deleted {
		return nil, ErrNotFound
	}
	cp := *fm
	return &cp, nil
}

// GetData looks up metadata then reads bytes from store.
func (d *Directory) GetData(bucket, filename string) (*meta.FileMeta, []byte, error) {
	fm, err := d.GetMeta(bucket, filename)
	if err != nil {
		return nil, nil, err
	}
	data, err := d.store.Read(fm.Vid, fm.Key, fm.Cookie)
	if err != nil {
		return nil, nil, err
	}
	return fm, data, nil
}

// Delete removes metadata and marks the needle deleted in store.
func (d *Directory) Delete(bucket, filename string) error {
	d.mu.Lock()
	defer d.mu.Unlock()
	k := fileKey(bucket, filename)
	fm, ok := d.files[k]
	if !ok {
		return ErrNotFound
	}
	if err := d.store.Delete(fm.Vid, fm.Key, fm.Cookie); err != nil {
		return err
	}
	tomb := *fm
	tomb.Deleted = true
	if err := d.appendMeta(&tomb); err != nil {
		return err
	}
	delete(d.files, k)
	delete(d.byKey, fm.Key)
	return nil
}

// List returns metadata for a bucket (non-deleted).
func (d *Directory) List(bucket string) []*meta.FileMeta {
	d.mu.RLock()
	defer d.mu.RUnlock()
	out := make([]*meta.FileMeta, 0)
	for _, fm := range d.files {
		if bucket != "" && fm.Bucket != bucket {
			continue
		}
		cp := *fm
		out = append(out, &cp)
	}
	return out
}

// Close closes the metadata file.
func (d *Directory) Close() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.metaFile == nil {
		return nil
	}
	err := d.metaFile.Close()
	d.metaFile = nil
	return err
}
