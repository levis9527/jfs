// Package store is the physical storage service (bfs store module).
// It owns volumes and exposes add / get / del operations.
package store

import (
	"errors"
	"fmt"
	"path/filepath"
	"sync"

	"github.com/levis9527/jfs/pkg/meta"
	"github.com/levis9527/jfs/pkg/volume"
)

var (
	ErrVolumeNotFound = errors.New("store: volume not found")
	ErrNoWritable     = errors.New("store: no writable volume")
)

// Store manages one or more volumes on local disk.
type Store struct {
	mu      sync.RWMutex
	root    string
	vols    map[int32]*volume.Volume
	maxSize int64
}

// Config for store initialization.
type Config struct {
	DataDir     string
	VolumeIDs   []int32
	VolumeSize  int64
	VolumeCount int // if VolumeIDs empty, create 1..VolumeCount
}

// Open initializes the store and opens volumes.
func Open(cfg Config) (*Store, error) {
	if cfg.DataDir == "" {
		return nil, errors.New("store: DataDir required")
	}
	if cfg.VolumeCount <= 0 && len(cfg.VolumeIDs) == 0 {
		cfg.VolumeCount = 1
	}
	ids := cfg.VolumeIDs
	if len(ids) == 0 {
		ids = make([]int32, cfg.VolumeCount)
		for i := 0; i < cfg.VolumeCount; i++ {
			ids[i] = int32(i + 1)
		}
	}
	s := &Store{
		root:    cfg.DataDir,
		vols:    make(map[int32]*volume.Volume),
		maxSize: cfg.VolumeSize,
	}
	for _, id := range ids {
		dir := filepath.Join(cfg.DataDir, fmt.Sprintf("volume_%d", id))
		vol, err := volume.Open(dir, id, volume.Options{MaxSize: cfg.VolumeSize})
		if err != nil {
			_ = s.Close()
			return nil, err
		}
		s.vols[id] = vol
	}
	return s, nil
}

// Write appends data into the given volume.
func (s *Store) Write(vid int32, key int64, cookie int32, data []byte) (meta.NeedleLoc, error) {
	vol, err := s.get(vid)
	if err != nil {
		return meta.NeedleLoc{}, err
	}
	return vol.Write(key, cookie, data)
}

// Read returns needle bytes from volume.
func (s *Store) Read(vid int32, key int64, cookie int32) ([]byte, error) {
	vol, err := s.get(vid)
	if err != nil {
		return nil, err
	}
	return vol.Read(key, cookie)
}

// Delete marks a needle deleted.
func (s *Store) Delete(vid int32, key int64, cookie int32) error {
	vol, err := s.get(vid)
	if err != nil {
		return err
	}
	return vol.Delete(key, cookie)
}

// PickVolume chooses a writable volume with the most free space.
func (s *Store) PickVolume(need int64) (int32, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	var best int32
	var bestFree int64 = -1
	for id, vol := range s.vols {
		st := vol.State()
		if st.ReadOnly {
			continue
		}
		if st.FreeSpace < need {
			continue
		}
		if st.FreeSpace > bestFree {
			bestFree = st.FreeSpace
			best = id
		}
	}
	if bestFree < 0 {
		return 0, ErrNoWritable
	}
	return best, nil
}

// States returns all volume states.
func (s *Store) States() []meta.VolumeState {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]meta.VolumeState, 0, len(s.vols))
	for _, vol := range s.vols {
		out = append(out, vol.State())
	}
	return out
}

func (s *Store) get(vid int32) (*volume.Volume, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	vol, ok := s.vols[vid]
	if !ok {
		return nil, ErrVolumeNotFound
	}
	return vol, nil
}

// Close closes all volumes.
func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	var first error
	for id, vol := range s.vols {
		if err := vol.Close(); err != nil && first == nil {
			first = err
		}
		delete(s.vols, id)
	}
	return first
}
