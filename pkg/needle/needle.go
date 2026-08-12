// Package needle implements the Haystack-style needle format used by bilibili bfs.
//
// needle file layout inside a volume (superblock), aligned to 8 bytes:
//
//	| magic(4) | cookie(4) | key(8) | flag(1) | size(4) | data | magic(4) | checksum(4) | padding |
package needle

import (
	"bytes"
	"encoding/binary"
	"errors"
	"hash/crc32"
)

const (
	MagicSize    = 4
	CookieSize   = 4
	KeySize      = 8
	FlagSize     = 1
	SizeSize     = 4
	ChecksumSize = 4

	HeaderSize = MagicSize + CookieSize + KeySize + FlagSize + SizeSize // 21
	FooterSize = MagicSize + ChecksumSize                               // 8

	PaddingSize = 8
	paddingMask = PaddingSize - 1

	FlagOK  = byte(0)
	FlagDel = byte(1)
)

var (
	headerMagic = []byte{0x12, 0x34, 0x56, 0x78}
	footerMagic = []byte{0x87, 0x65, 0x43, 0x21}
	crcTable    = crc32.MakeTable(crc32.Koopman)

	ErrHeaderMagic = errors.New("needle: bad header magic")
	ErrFooterMagic = errors.New("needle: bad footer magic")
	ErrChecksum    = errors.New("needle: checksum mismatch")
	ErrFlag        = errors.New("needle: invalid flag")
	ErrSize        = errors.New("needle: invalid size")
	ErrDeleted     = errors.New("needle: deleted")
	ErrCookie      = errors.New("needle: cookie mismatch")
	ErrKey         = errors.New("needle: key mismatch")
)

// Needle is one small file record inside a volume.
type Needle struct {
	Cookie     int32
	Key        int64
	Flag       byte
	Size       int32
	Data       []byte
	Checksum   uint32
	TotalSize  int32
	PaddingLen int32
	Offset     uint32 // aligned offset units (byte_offset / PaddingSize)
}

// Align rounds n up to PaddingSize.
func Align(n int32) int32 {
	return (n + paddingMask) &^ paddingMask
}

// NeedleOffset converts a byte offset into an aligned uint32 offset unit.
func NeedleOffset(offset int64) uint32 {
	return uint32(offset / PaddingSize)
}

// BlockOffset converts an aligned offset unit back to a byte offset.
func BlockOffset(offset uint32) int64 {
	return int64(offset) * PaddingSize
}

// CalcTotalSize returns the padded on-disk size for a given data size.
func CalcTotalSize(dataSize int32) int32 {
	return Align(HeaderSize + dataSize + FooterSize)
}

// Encode builds a full needle buffer for append write.
func Encode(key int64, cookie int32, data []byte) ([]byte, *Needle, error) {
	if len(data) > int(^uint32(0)>>1) {
		return nil, nil, ErrSize
	}
	n := &Needle{
		Cookie:   cookie,
		Key:      key,
		Flag:     FlagOK,
		Size:     int32(len(data)),
		Data:     data,
		Checksum: crc32.Checksum(data, crcTable),
	}
	n.TotalSize = CalcTotalSize(n.Size)
	n.PaddingLen = n.TotalSize - (HeaderSize + n.Size + FooterSize)

	buf := make([]byte, n.TotalSize)
	// header
	copy(buf[0:4], headerMagic)
	binary.BigEndian.PutUint32(buf[4:8], uint32(cookie))
	binary.BigEndian.PutUint64(buf[8:16], uint64(key))
	buf[16] = FlagOK
	binary.BigEndian.PutUint32(buf[17:21], uint32(n.Size))
	// data
	copy(buf[21:21+n.Size], data)
	// footer
	footerOff := 21 + n.Size
	copy(buf[footerOff:footerOff+4], footerMagic)
	binary.BigEndian.PutUint32(buf[footerOff+4:footerOff+8], n.Checksum)
	return buf, n, nil
}

// Decode parses a complete needle buffer (must include padding).
func Decode(buf []byte) (*Needle, error) {
	if len(buf) < HeaderSize+FooterSize {
		return nil, ErrSize
	}
	if !bytes.Equal(buf[0:4], headerMagic) {
		return nil, ErrHeaderMagic
	}
	n := &Needle{
		Cookie: int32(binary.BigEndian.Uint32(buf[4:8])),
		Key:    int64(binary.BigEndian.Uint64(buf[8:16])),
		Flag:   buf[16],
		Size:   int32(binary.BigEndian.Uint32(buf[17:21])),
	}
	if n.Flag != FlagOK && n.Flag != FlagDel {
		return nil, ErrFlag
	}
	if n.Size < 0 {
		return nil, ErrSize
	}
	n.TotalSize = CalcTotalSize(n.Size)
	if int32(len(buf)) < n.TotalSize {
		return nil, ErrSize
	}
	dataOff := HeaderSize
	footerOff := dataOff + int(n.Size)
	n.Data = buf[dataOff:footerOff]
	if !bytes.Equal(buf[footerOff:footerOff+4], footerMagic) {
		return nil, ErrFooterMagic
	}
	n.Checksum = binary.BigEndian.Uint32(buf[footerOff+4 : footerOff+8])
	if crc32.Checksum(n.Data, crcTable) != n.Checksum {
		return nil, ErrChecksum
	}
	n.PaddingLen = n.TotalSize - (HeaderSize + n.Size + FooterSize)
	return n, nil
}

// MarkDeleted returns the on-disk flag offset within a needle (relative to needle start).
func FlagOffset() int64 {
	return 16
}
