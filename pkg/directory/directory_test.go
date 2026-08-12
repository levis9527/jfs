package directory_test

import (
	"bytes"
	"path/filepath"
	"testing"

	"github.com/levis9527/jfs/pkg/directory"
	"github.com/levis9527/jfs/pkg/store"
)

func TestUploadGetDelete(t *testing.T) {
	root := t.TempDir()
	st, err := store.Open(store.Config{
		DataDir:     filepath.Join(root, "store"),
		VolumeCount: 2,
		VolumeSize:  1 << 20,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	dir, err := directory.Open(filepath.Join(root, "meta"), st, 1)
	if err != nil {
		t.Fatal(err)
	}
	defer dir.Close()

	data := []byte{0xff, 0xd8, 0xff, 0xe0} // fake jpeg header
	up, err := dir.Upload("img", "a.jpg", "image/jpeg", data)
	if err != nil {
		t.Fatal(err)
	}
	if up.Key == 0 || up.Vid == 0 {
		t.Fatalf("bad upload: %+v", up)
	}

	fm, got, err := dir.GetData("img", "a.jpg")
	if err != nil {
		t.Fatal(err)
	}
	if fm.Mime != "image/jpeg" || !bytes.Equal(got, data) {
		t.Fatalf("mismatch meta=%+v data=%v", fm, got)
	}

	if _, err := dir.Upload("img", "a.jpg", "image/jpeg", data); err != directory.ErrExists {
		t.Fatalf("want exists, got %v", err)
	}

	if err := dir.Delete("img", "a.jpg"); err != nil {
		t.Fatal(err)
	}
	if _, _, err := dir.GetData("img", "a.jpg"); err != directory.ErrNotFound {
		t.Fatalf("want not found, got %v", err)
	}
}

func TestMetadataPersistence(t *testing.T) {
	root := t.TempDir()
	storeDir := filepath.Join(root, "store")
	metaDir := filepath.Join(root, "meta")

	st, err := store.Open(store.Config{DataDir: storeDir, VolumeCount: 1, VolumeSize: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	dir, err := directory.Open(metaDir, st, 2)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := dir.Upload("b", "f.bin", "application/octet-stream", []byte("xyz")); err != nil {
		t.Fatal(err)
	}
	_ = dir.Close()
	_ = st.Close()

	st2, err := store.Open(store.Config{DataDir: storeDir, VolumeCount: 1, VolumeSize: 1 << 20})
	if err != nil {
		t.Fatal(err)
	}
	defer st2.Close()
	dir2, err := directory.Open(metaDir, st2, 2)
	if err != nil {
		t.Fatal(err)
	}
	defer dir2.Close()
	_, got, err := dir2.GetData("b", "f.bin")
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "xyz" {
		t.Fatalf("got %q", got)
	}
}
