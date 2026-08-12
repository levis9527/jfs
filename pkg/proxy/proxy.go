// Package proxy exposes the public HTTP API (bfs proxy module).
package proxy

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/levis9527/jfs/pkg/directory"
	"github.com/levis9527/jfs/pkg/meta"
	"github.com/levis9527/jfs/pkg/store"
)

// Server is the HTTP facade over directory + store.
type Server struct {
	dir   *directory.Directory
	store *store.Store
	mux   *http.ServeMux
}

// New creates an HTTP API server.
func New(dir *directory.Directory, st *store.Store) *Server {
	s := &Server{dir: dir, store: st, mux: http.NewServeMux()}
	s.routes()
	return s
}

func (s *Server) routes() {
	s.mux.HandleFunc("/ping", s.ping)
	s.mux.HandleFunc("/stats", s.stats)
	s.mux.HandleFunc("/upload", s.upload)
	s.mux.HandleFunc("/get", s.get)
	s.mux.HandleFunc("/del", s.del)
	s.mux.HandleFunc("/list", s.list)
	s.mux.HandleFunc("/", s.restObject)
}

// Handler returns the root handler.
func (s *Server) Handler() http.Handler { return s.mux }

func writeJSON(w http.ResponseWriter, code int, ret int, msg string, data interface{}) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(meta.APIResponse{Ret: ret, Msg: msg, Data: data})
}

func (s *Server) ping(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, meta.RetOK, "pong", nil)
}

func (s *Server) stats(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, meta.RetOK, "", s.store.States())
}

func (s *Server) upload(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, meta.RetBadRequest, "POST required", nil)
		return
	}
	bucket := r.FormValue("bucket")
	filename := r.FormValue("filename")
	mime := r.FormValue("mime")
	if bucket == "" || filename == "" {
		// also support multipart
		if err := r.ParseMultipartForm(32 << 20); err == nil {
			bucket = r.FormValue("bucket")
			filename = r.FormValue("filename")
			mime = r.FormValue("mime")
			file, hdr, err := r.FormFile("file")
			if err == nil {
				defer file.Close()
				if filename == "" {
					filename = hdr.Filename
				}
				if mime == "" {
					mime = hdr.Header.Get("Content-Type")
				}
				data, err := io.ReadAll(file)
				if err != nil {
					writeJSON(w, http.StatusInternalServerError, meta.RetInternalErr, err.Error(), nil)
					return
				}
				s.doUpload(w, bucket, filename, mime, data)
				return
			}
		}
		writeJSON(w, http.StatusBadRequest, meta.RetBadRequest, "bucket/filename required", nil)
		return
	}
	data, err := io.ReadAll(io.LimitReader(r.Body, 64<<20))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, meta.RetBadRequest, err.Error(), nil)
		return
	}
	s.doUpload(w, bucket, filename, mime, data)
}

func (s *Server) doUpload(w http.ResponseWriter, bucket, filename, mime string, data []byte) {
	res, err := s.dir.Upload(bucket, filename, mime, data)
	if err != nil {
		if errors.Is(err, directory.ErrExists) {
			writeJSON(w, http.StatusConflict, meta.RetConflict, err.Error(), nil)
			return
		}
		writeJSON(w, http.StatusInternalServerError, meta.RetInternalErr, err.Error(), nil)
		return
	}
	writeJSON(w, http.StatusOK, meta.RetOK, "", res)
}

func (s *Server) get(w http.ResponseWriter, r *http.Request) {
	bucket := r.URL.Query().Get("bucket")
	filename := r.URL.Query().Get("filename")
	if bucket == "" || filename == "" {
		writeJSON(w, http.StatusBadRequest, meta.RetBadRequest, "bucket/filename required", nil)
		return
	}
	fm, data, err := s.dir.GetData(bucket, filename)
	if err != nil {
		if errors.Is(err, directory.ErrNotFound) {
			writeJSON(w, http.StatusNotFound, meta.RetNotFound, err.Error(), nil)
			return
		}
		writeJSON(w, http.StatusInternalServerError, meta.RetInternalErr, err.Error(), nil)
		return
	}
	if r.URL.Query().Get("meta") == "1" {
		writeJSON(w, http.StatusOK, meta.RetOK, "", fm)
		return
	}
	if fm.Mime != "" {
		w.Header().Set("Content-Type", fm.Mime)
	} else {
		w.Header().Set("Content-Type", "application/octet-stream")
	}
	w.Header().Set("X-JFS-Key", itoa(fm.Key))
	w.Header().Set("X-JFS-Vid", itoa32(fm.Vid))
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

func (s *Server) del(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost && r.Method != http.MethodDelete {
		writeJSON(w, http.StatusMethodNotAllowed, meta.RetBadRequest, "POST/DELETE required", nil)
		return
	}
	bucket := r.FormValue("bucket")
	filename := r.FormValue("filename")
	if bucket == "" {
		bucket = r.URL.Query().Get("bucket")
		filename = r.URL.Query().Get("filename")
	}
	if bucket == "" || filename == "" {
		writeJSON(w, http.StatusBadRequest, meta.RetBadRequest, "bucket/filename required", nil)
		return
	}
	if err := s.dir.Delete(bucket, filename); err != nil {
		if errors.Is(err, directory.ErrNotFound) {
			writeJSON(w, http.StatusNotFound, meta.RetNotFound, err.Error(), nil)
			return
		}
		writeJSON(w, http.StatusInternalServerError, meta.RetInternalErr, err.Error(), nil)
		return
	}
	writeJSON(w, http.StatusOK, meta.RetOK, "deleted", nil)
}

func (s *Server) list(w http.ResponseWriter, r *http.Request) {
	bucket := r.URL.Query().Get("bucket")
	writeJSON(w, http.StatusOK, meta.RetOK, "", s.dir.List(bucket))
}

// restObject supports PUT/GET/DELETE /{bucket}/{filename}
func (s *Server) restObject(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/")
	if path == "" {
		writeJSON(w, http.StatusOK, meta.RetOK, "jfs ready", map[string]string{
			"upload": "POST /upload",
			"get":    "GET /get?bucket=&filename=",
			"del":    "POST /del",
			"rest":   "PUT|GET|DELETE /{bucket}/{filename}",
		})
		return
	}
	parts := strings.SplitN(path, "/", 2)
	if len(parts) != 2 || parts[0] == "" || parts[1] == "" {
		writeJSON(w, http.StatusBadRequest, meta.RetBadRequest, "use /{bucket}/{filename}", nil)
		return
	}
	bucket, filename := parts[0], parts[1]
	switch r.Method {
	case http.MethodPut, http.MethodPost:
		data, err := io.ReadAll(io.LimitReader(r.Body, 64<<20))
		if err != nil {
			writeJSON(w, http.StatusBadRequest, meta.RetBadRequest, err.Error(), nil)
			return
		}
		mime := r.Header.Get("Content-Type")
		s.doUpload(w, bucket, filename, mime, data)
	case http.MethodGet:
		fm, data, err := s.dir.GetData(bucket, filename)
		if err != nil {
			if errors.Is(err, directory.ErrNotFound) {
				http.NotFound(w, r)
				return
			}
			writeJSON(w, http.StatusInternalServerError, meta.RetInternalErr, err.Error(), nil)
			return
		}
		if fm.Mime != "" {
			w.Header().Set("Content-Type", fm.Mime)
		}
		_, _ = w.Write(data)
	case http.MethodDelete:
		if err := s.dir.Delete(bucket, filename); err != nil {
			if errors.Is(err, directory.ErrNotFound) {
				http.NotFound(w, r)
				return
			}
			writeJSON(w, http.StatusInternalServerError, meta.RetInternalErr, err.Error(), nil)
			return
		}
		writeJSON(w, http.StatusOK, meta.RetOK, "deleted", nil)
	default:
		writeJSON(w, http.StatusMethodNotAllowed, meta.RetBadRequest, "method not allowed", nil)
	}
}

func itoa(v int64) string {
	return jsonNumber(v)
}

func itoa32(v int32) string {
	return jsonNumber(int64(v))
}

func jsonNumber(v int64) string {
	b, _ := json.Marshal(v)
	return string(b)
}
