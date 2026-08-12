# jfs

`jfs` 是参考 [bilibili bfs](https://github.com/Terry-Mao/bfs)（基于 Facebook Haystack、用 Go 实现的小文件存储）设计的轻量小文件存储系统。

目标场景：图片等小文件的高吞吐写入与按 key 快速读取。核心做法是**把小文件合并进大卷（volume）**，元数据与物理数据**模块拆分**。

## 架构（对照 bfs）

| 模块 | bfs | jfs |
|------|-----|-----|
| **store** | 物理存储，needle 追加写入 volume | `pkg/store` + `pkg/volume` + `pkg/needle` |
| **directory** | 调度 + 元数据（HBase）+ 分配 key（snowflake） | `pkg/directory`（本地 JSONL 元数据）+ `pkg/idgen` |
| **proxy** | 对外 HTTP / bucket API | `pkg/proxy` |
| pitchfork / ops / ZK | 监控与运维 | 简化：`/ping`、`/stats`（无 ZK/HBase 依赖） |

请求路径：

```
Client -> proxy (HTTP)
           |-- directory: 查/写元数据，分配 key/cookie/vid
           `-- store:     按 vid 在 volume 中读写 needle
```

## 存储模型

### Needle（小文件）

与 bfs 相同的对齐格式（8 字节对齐）：

```
| magic(4) | cookie(4) | key(8) | flag(1) | size(4) | data | magic(4) | crc32(4) | padding |
```

- **key**：全局唯一文件 ID（snowflake）
- **cookie**：防穷举访问
- **flag**：正常 / 删除（删除只改标记，空间由后续 compact 回收；当前版本为标记删除）

### Volume（超级块）

每个 volume = `{id}.dat`（数据） + `{id}.idx`（索引）

索引记录（16 字节）：

```
| key(int64) | offset(uint32, 对齐单位) | size(int32) |
```

内存中维护 `key -> offset`，读取只需一次 `ReadAt`。

### Directory 元数据

用户视角：`bucket + filename` → `{key, cookie, vid, size, mime}`

持久化：`files.jsonl`（追加写；删除写 tombstone）。重启后重建内存索引，再向 store 读真实数据。

## 快速开始

```bash
go test ./...
go run ./cmd/jfs -addr :8080 -data ./data -volumes 2
```

### API

上传：

```bash
curl -X PUT --data-binary @photo.jpg http://127.0.0.1:8080/img/photo.jpg \
  -H 'Content-Type: image/jpeg'
```

或：

```bash
curl -X POST "http://127.0.0.1:8080/upload?bucket=img&filename=photo.jpg&mime=image/jpeg" \
  --data-binary @photo.jpg
```

下载：

```bash
curl -o out.jpg http://127.0.0.1:8080/img/photo.jpg
curl "http://127.0.0.1:8080/get?bucket=img&filename=photo.jpg"
```

删除 / 列表 / 状态：

```bash
curl -X DELETE http://127.0.0.1:8080/img/photo.jpg
curl "http://127.0.0.1:8080/list?bucket=img"
curl http://127.0.0.1:8080/stats
```

## 目录结构

```
cmd/jfs/           一体机入口（store + directory + proxy）
pkg/needle/        needle 编解码
pkg/index/         volume 索引
pkg/volume/        volume（superblock + 内存 map）
pkg/store/         多 volume 管理与选卷
pkg/directory/     元数据与上传调度
pkg/proxy/         HTTP API
pkg/idgen/         snowflake key
pkg/meta/          公共结构体
```

## 与 bfs 的差异

- **无 ZooKeeper / HBase**：本地文件即可运行，适合学习与单机部署
- **一体机进程**：模块边界保留，默认同进程组装；后续可拆成独立服务
- **未实现**：在线 compact、跨机镜像复制、pitchfork 探针、ops 后台

## License

Apache License 2.0
