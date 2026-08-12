// Package meta defines shared metadata types used across jfs modules.
// Inspired by bilibili bfs (Terry-Mao/bfs) and Facebook Haystack.
package meta

// NeedleLoc is the logical location of a small file inside a volume.
type NeedleLoc struct {
	Key    int64  `json:"key"`
	Cookie int32  `json:"cookie"`
	Vid    int32  `json:"vid"`
	Offset uint32 `json:"offset"`
	Size   int32  `json:"size"`
}

// FileMeta is the user-facing metadata stored by the directory service.
// Mapping: bucket + filename -> needle location (+ optional mime).
type FileMeta struct {
	Bucket   string `json:"bucket"`
	Filename string `json:"filename"`
	Mime     string `json:"mime,omitempty"`
	Key      int64  `json:"key"`
	Cookie   int32  `json:"cookie"`
	Vid      int32  `json:"vid"`
	Size     int32  `json:"size"`
	Created  int64  `json:"created"`
	Deleted  bool   `json:"deleted,omitempty"`
}

// UploadResult is returned after a successful write.
type UploadResult struct {
	Bucket   string `json:"bucket"`
	Filename string `json:"filename"`
	Key      int64  `json:"key"`
	Cookie   int32  `json:"cookie"`
	Vid      int32  `json:"vid"`
	Size     int32  `json:"size"`
	URL      string `json:"url,omitempty"`
}

// VolumeState describes writable capacity of a volume for scheduling.
type VolumeState struct {
	ID        int32  `json:"id"`
	Path      string `json:"path"`
	FreeSpace int64  `json:"free_space"`
	FileCount int    `json:"file_count"`
	ReadOnly  bool   `json:"read_only"`
}

// APIResponse is a generic JSON envelope.
type APIResponse struct {
	Ret  int         `json:"ret"`
	Msg  string      `json:"msg,omitempty"`
	Data interface{} `json:"data,omitempty"`
}

const (
	RetOK          = 1
	RetNotFound    = 404
	RetBadRequest  = 400
	RetInternalErr = 500
	RetConflict    = 409
)
