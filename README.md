# jfs

`jfs` 是参考 [bilibili bfs](https://github.com/Terry-Mao/bfs)（基于 Facebook Haystack、用 **Go** 实现的小文件存储）设计、用 **Java** 实现的轻量小文件存储系统。

目标场景：图片等小文件的高吞吐写入与按 key 快速读取。核心做法是**把小文件合并进大卷（volume）**，元数据与物理数据**模块拆分**。

## 架构（对照 bfs）

| 模块 | bfs (Go) | jfs (Java) |
|------|----------|------------|
| **store** | 物理存储，needle 追加写入 volume | `com.levis9527.jfs.store` / `volume` / `needle` |
| **directory** | 调度 + 元数据（HBase）+ 分配 key | `com.levis9527.jfs.directory` + `idgen`（本地 JSONL） |
| **proxy** | 对外 HTTP / bucket API | `com.levis9527.jfs.proxy`（JDK `HttpServer`） |
| pitchfork / ops / ZK | 监控与运维 | 简化：`/ping`、`/stats`（无 ZK/HBase） |

请求路径：

```
Client -> proxy (HTTP)
           |-- directory: 查/写元数据，分配 key/cookie/vid
           `-- store:     按 vid 在 volume 中读写 needle
```

## 存储模型

### Needle（小文件）

与 bfs 相同的对齐思路（8 字节对齐）：

```
| magic(4) | cookie(4) | key(8) | flag(1) | size(4) | data | magic(4) | crc32(4) | padding |
```

- **key**：全局唯一文件 ID（snowflake）
- **cookie**：防穷举访问
- **flag**：正常 / 删除（标记删除）

### Volume（超级块）

每个 volume = `{id}.dat`（数据） + `{id}.idx`（索引）

索引记录（16 字节）：

```
| key(int64) | offset(uint32, 对齐单位) | size(int32) |
```

### Directory 元数据

用户视角：`bucket + filename` → `{key, cookie, vid, size, mime}`

持久化：`files.jsonl`（追加写；删除写 tombstone）。

## 快速开始

要求：JDK 21+、Maven 3.9+

```bash
mvn test
mvn -q package
java -jar target/jfs-0.1.0-SNAPSHOT.jar -addr :8080 -data ./data -volumes 2
```

浏览器打开控制台：http://127.0.0.1:8080/admin

- 总览：文件数、bucket、对象体积、volume 占用
- 文件与元数据：列表、搜索、预览、改文件名/MIME、下载、删除
- 上传：选择文件写入指定 bucket

### API

```bash
# 上传
curl -X PUT --data-binary @photo.jpg http://127.0.0.1:8080/img/photo.jpg \
  -H 'Content-Type: image/jpeg'

# 下载
curl -o out.jpg http://127.0.0.1:8080/img/photo.jpg

# 删除 / 列表 / 状态
curl -X DELETE http://127.0.0.1:8080/img/photo.jpg
curl "http://127.0.0.1:8080/list?bucket=img"
curl http://127.0.0.1:8080/overview
curl http://127.0.0.1:8080/stats
```

## 目录结构

```
src/main/java/com/levis9527/jfs/
  JfsServer.java          一体机入口
  needle/                 needle 编解码
  index/                  volume 索引
  volume/                 volume（superblock + 内存 map）
  store/                  多 volume 管理与选卷
  directory/              元数据与上传调度
  proxy/                  HTTP API + /admin 控制台
  idgen/                  snowflake key
  meta/                   公共结构体
src/main/resources/web/   管理页面（HTML/CSS/JS）
```

## 与 bfs 的差异

- **语言**：Java（本仓库），参考对象为 Go 版 bfs
- **无 ZooKeeper / HBase**：本地文件即可运行
- **一体机进程**：模块边界保留，默认同进程组装
- **未实现**：在线 compact、跨机镜像复制、pitchfork、ops 后台

## License

Apache License 2.0
