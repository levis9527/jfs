package idgen_test

import (
	"testing"

	"github.com/levis9527/jfs/pkg/idgen"
)

func TestUniqueIDs(t *testing.T) {
	g, err := idgen.New(1)
	if err != nil {
		t.Fatal(err)
	}
	seen := make(map[int64]struct{}, 1000)
	for i := 0; i < 1000; i++ {
		id, err := g.Next()
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := seen[id]; ok {
			t.Fatalf("duplicate id %d", id)
		}
		seen[id] = struct{}{}
		if idgen.NextCookie(id) == 0 {
			t.Fatal("cookie should be non-zero")
		}
	}
}
