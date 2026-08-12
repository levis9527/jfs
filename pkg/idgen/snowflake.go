// Package idgen provides a lightweight snowflake-like ID generator.
// bfs uses gosnowflake; jfs embeds a local worker for zero external deps.
package idgen

import (
	"errors"
	"sync"
	"time"
)

const (
	epoch          int64 = 1609459200000 // 2021-01-01 UTC, ms
	workerBits           = 10
	sequenceBits         = 12
	maxWorkerID          = -1 ^ (-1 << workerBits)
	maxSequence          = -1 ^ (-1 << sequenceBits)
	workerShift          = sequenceBits
	timestampShift       = sequenceBits + workerBits
)

// Generator produces unique int64 keys.
type Generator struct {
	mu        sync.Mutex
	workerID  int64
	sequence  int64
	lastStamp int64
}

// New creates a generator bound to workerID (0..1023).
func New(workerID int64) (*Generator, error) {
	if workerID < 0 || workerID > maxWorkerID {
		return nil, errors.New("idgen: worker id out of range")
	}
	return &Generator{workerID: workerID}, nil
}

// Next returns a unique int64 key.
func (g *Generator) Next() (int64, error) {
	g.mu.Lock()
	defer g.mu.Unlock()

	now := time.Now().UnixMilli()
	if now < g.lastStamp {
		return 0, errors.New("idgen: clock moved backwards")
	}
	if now == g.lastStamp {
		g.sequence = (g.sequence + 1) & maxSequence
		if g.sequence == 0 {
			for now <= g.lastStamp {
				now = time.Now().UnixMilli()
			}
		}
	} else {
		g.sequence = 0
	}
	g.lastStamp = now
	id := ((now - epoch) << timestampShift) | (g.workerID << workerShift) | g.sequence
	return id, nil
}

// NextCookie returns a non-zero random-ish cookie derived from the key.
func NextCookie(key int64) int32 {
	c := int32(key ^ (key >> 32))
	if c == 0 {
		return 1
	}
	if c < 0 {
		return -c
	}
	return c
}
