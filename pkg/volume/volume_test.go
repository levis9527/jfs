package volume_test

import (
	"bytes"
	"path/filepath"
	"testing"

	"github.com/levis9527/jfs/pkg/volume"
)

func TestVolumeWriteReadDelete(t *testing.T) {
	dir := t.TempDir()
	v, err := volume.Open(dir, 1, volume.Options{MaxSize: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	defer v.Close()

	payload := []byte("image-bytes-001")
	loc, err := v.Write(1001, 7, payload)
	if err != nil {
		t.Fatal(err)
	}
	if loc.Vid != 1 || loc.Key != 1001 {
		t.Fatalf("bad loc: %+v", loc)
	}

	got, err := v.Read(1001, 7)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, payload) {
		t.Fatalf("got %q", got)
	}

	if _, err := v.Read(1001, 8); err != volume.ErrBadCookie {
		t.Fatalf("want bad cookie, got %v", err)
	}

	if err := v.Delete(1001, 7); err != nil {
		t.Fatal(err)
	}
	if _, err := v.Read(1001, 7); err != volume.ErrNotFound {
		t.Fatalf("want not found after delete, got %v", err)
	}
}

func TestVolumeRecoveryFromIndex(t *testing.T) {
	dir := t.TempDir()
	v, err := volume.Open(filepath.Join(dir, "v"), 2, volume.Options{MaxSize: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	_, err = v.Write(42, 3, []byte("persist-me"))
	if err != nil {
		t.Fatal(err)
	}
	if err := v.Close(); err != nil {
		t.Fatal(err)
	}

	v2, err := volume.Open(filepath.Join(dir, "v"), 2, volume.Options{MaxSize: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	defer v2.Close()
	got, err := v2.Read(42, 3)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "persist-me" {
		t.Fatalf("got %q", got)
	}
}
