# jfs

`jfs` 是参考 [bilibili bfs](https://github.com/Terry-Mao/bfs)（基于 Facebook Haystack）设计、用 **Java 21** 实现的轻量小文件存储。

适合图片等小文件：顺序追加写入大卷（volume），元数据与物理数据模块拆分。详细编译、启动、API 与排错见 **[启动与使用说明](docs/startup.md)**。

## 目录

- [架构](#架构对照-bfs)
- [存储模型](#存储模型)
- [启动与使用](#启动与使用)
- [管理控制台](#管理控制台)
- [HTTP API 速查](#http-api-速查)
- [目录结构](#目录结构)
- [与 bfs 的差异](#与-bfs-的差异)

## 架构（对照 bfs）

| 模块 | bfs (Go) | jfs (Java) |
|------|----------|------------|
| **store** | 物理存储，needle 追加写入 volume | `com.levis9527.jfs.store` / `volume` / `needle` |
| **directory** | 调度 + 元数据（HBase）+ 分配 key | `com.levis9527.jfs.directory` + `idgen`（本地 JSONL） |
| **proxy** | 对外 HTTP / bucket API | `com.levis9527.jfs.proxy`（JDK `HttpServer`） |
| pitchfork / ops / ZK | 监控与运维 | `/admin` 控制台 + `/ping` `/overview` `/stats` |

请求路径：

```
Client / 浏览器
    -> proxy  HTTP API 与 /admin
         |-- directory  查/写元数据，分配 key、cookie、vid
         `-- store      按 vid 在 volume 中读写 needle
```

## 存储模型

### Needle（小文件）

8 字节对齐：

```
| magic(4) | cookie(4) | key(8) | flag(1) | size(4) | data | magic(4) | crc32(4) | padding |
```

- **key**：全局唯一文件 ID（snowflake）
- **cookie**：防穷举访问
- **flag**：正常 / 删除（标记删除）

### Volume（超级块）

每个 volume = `{id}.dat`（数据） + `{id}.idx`（索引）

索引记录（16 字节）：`key(int64) | offset(uint32, 对齐单位) | size(int32)`

### Directory 元数据

`bucket + filename` → `{key, cookie, vid, size, mime}`

持久化文件：数据目录下的 `files.jsonl`（追加写；删除与重命名写 tombstone）。

## 启动与使用

要求：**JDK 21+**、**Maven 3.9+**。逐步说明、全部参数、数据目录和排错见 [docs/startup.md](docs/startup.md)。

### 编译

```bash
mvn test
mvn -q package
```

### 启动

```bash
java -jar target/jfs-0.1.0-SNAPSHOT.jar \
  -addr :8080 \
  -data ./data \
  -volumes 2 \
  -volume-size 1073741824 \
  -worker 1
```

成功日志：

```text
jfs listening on 0.0.0.0:8080 (data=.../data volumes=2)
```

| 参数 | 默认 | 说明 |
|------|------|------|
| `-addr` | `:8080` | `:端口`、`host:端口` 或仅端口 |
| `-data` | `./data` | 数据根目录 |
| `-volumes` | `2` | volume 个数 |
| `-volume-size` | `1073741824` | 单卷最大字节（默认 1 GiB） |
| `-worker` | `1` | snowflake worker id（0–1023） |
| `-h` / `--help` | | 打印帮助 |

`configs/jfs.conf` 只是参数备忘，**不会被自动加载**。

### 验证

```bash
curl -s http://127.0.0.1:8080/ping
./examples/demo.sh http://127.0.0.1:8080
```

停止：前台用 `Ctrl+C`；后台记下 PID 后 `kill`。

## 管理控制台

浏览器打开：**http://127.0.0.1:8080/admin**

（访问 `/` 且 Accept 为 HTML 时会跳转到 `/admin`）

- **总览**：文件数、bucket、对象体积、volume 占用
- **文件与元数据**：列表、搜索、预览、改文件名/MIME、下载、删除
- **上传**：选择文件写入指定 bucket

## HTTP API 速查

JSON 包装：`{"ret":1,"msg":"...","data":...}`。`ret=1` 表示成功。

```bash
# 上传 / 下载 / 删除（REST）
curl -X PUT --data-binary @photo.jpg -H 'Content-Type: image/jpeg' \
  http://127.0.0.1:8080/img/photo.jpg
curl -o out.jpg http://127.0.0.1:8080/img/photo.jpg
curl -X DELETE http://127.0.0.1:8080/img/photo.jpg

# 列表、总览、改元数据
curl "http://127.0.0.1:8080/list?bucket=img"
curl http://127.0.0.1:8080/overview
curl -X POST "http://127.0.0.1:8080/meta?bucket=img&filename=photo.jpg&newFilename=cover.jpg&mime=image/jpeg"
```

完整接口表见 [docs/startup.md](docs/startup.md#7-http-api)。

## 目录结构

```
docs/startup.md           启动、参数、API、排错
examples/demo.sh          启动后冒烟脚本
configs/jfs.conf          启动参数备忘（需手动抄到命令行）
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

数据目录（`-data`）启动后：

```
data/files.jsonl
data/volume_1/1.dat  1.idx
data/volume_2/2.dat  2.idx
```

## 与 bfs 的差异

- **语言**：Java；参考对象为 Go 版 bfs
- **无 ZooKeeper / HBase**：本地文件即可运行
- **一体机进程**：模块边界保留，默认同进程组装
- **运维界面**：内置 `/admin`，不是独立 ops 服务
- **未实现**：在线 compact、跨机镜像复制、pitchfork

## License

Apache License 2.0
