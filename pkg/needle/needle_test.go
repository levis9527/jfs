package needle_test

import (
	"bytes"
	"testing"

	"github.com/levis9527/jfs/pkg/needle"
)

func TestEncodeDecode(t *testing.T) {
	data := []byte("hello-jfs-small-file")
	buf, n, err := needle.Encode(12345, 99, data)
	if err != nil {
		t.Fatal(err)
	}
	if n.TotalSize%needle.PaddingSize != 0 {
		t.Fatalf("not aligned: %d", n.TotalSize)
	}
	got, err := needle.Decode(buf)
	if err != nil {
		t.Fatal(err)
	}
	if got.Key != 12345 || got.Cookie != 99 {
		t.Fatalf("key/cookie mismatch: %+v", got)
	}
	if !bytes.Equal(got.Data, data) {
		t.Fatalf("data mismatch: %q", got.Data)
	}
}

func TestOffsetAlign(t *testing.T) {
	if needle.NeedleOffset(16) != 2 {
		t.Fatal("NeedleOffset")
	}
	if needle.BlockOffset(2) != 16 {
		t.Fatal("BlockOffset")
	}
	if needle.Align(21) != 24 {
		t.Fatalf("Align got %d", needle.Align(21))
	}
}

func TestCorruptChecksum(t *testing.T) {
	buf, _, err := needle.Encode(1, 1, []byte("abc"))
	if err != nil {
		t.Fatal(err)
	}
	buf[21] ^= 0xff
	if _, err := needle.Decode(buf); err != needle.ErrChecksum {
		t.Fatalf("want checksum err, got %v", err)
	}
}
