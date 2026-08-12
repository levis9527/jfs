// Command jfs runs an all-in-one small-file storage server.
// Modules mirror bilibili bfs: store (data) + directory (metadata) + proxy (API).
package main

import (
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"

	"github.com/levis9527/jfs/pkg/directory"
	"github.com/levis9527/jfs/pkg/proxy"
	"github.com/levis9527/jfs/pkg/store"
)

func main() {
	var (
		addr        = flag.String("addr", ":8080", "HTTP listen address")
		dataDir     = flag.String("data", "./data", "data root directory")
		volumes     = flag.Int("volumes", 2, "number of volumes")
		volumeSize  = flag.Int64("volume-size", 1<<30, "max size per volume in bytes")
		workerID    = flag.Int64("worker", 1, "snowflake worker id (0-1023)")
	)
	flag.Parse()

	st, err := store.Open(store.Config{
		DataDir:     *dataDir,
		VolumeCount: *volumes,
		VolumeSize:  *volumeSize,
	})
	if err != nil {
		log.Fatalf("open store: %v", err)
	}
	defer st.Close()

	dir, err := directory.Open(*dataDir, st, *workerID)
	if err != nil {
		log.Fatalf("open directory: %v", err)
	}
	defer dir.Close()

	srv := proxy.New(dir, st)
	httpServer := &http.Server{Addr: *addr, Handler: srv.Handler()}

	go func() {
		log.Printf("jfs listening on %s (data=%s volumes=%d)", *addr, *dataDir, *volumes)
		if err := httpServer.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("http: %v", err)
		}
	}()

	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGINT, syscall.SIGTERM)
	sig := <-ch
	fmt.Printf("\nshutting down (%s)...\n", sig)
	_ = httpServer.Close()
}
