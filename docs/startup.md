# jfs 启动与使用说明

本文说明如何编译、启动、验证和使用 jfs（Java 小文件存储）。更完整的架构说明见仓库根目录 [README.md](../README.md)。

## 1. 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK | 21+ | 编译与运行都需要 |
| Maven | 3.9+ | 构建测试与打包 |
| curl / 浏览器 | 任意 | 调用 API 或打开管理页 |

检查：

```bash
java -version
mvn -v
```

## 2. 获取代码并编译

```bash
git clone https://github.com/levis9527/jfs.git
cd jfs

# 运行单元测试
mvn test

# 打包可执行 fat jar（含依赖）
mvn -q package
```

产物：

| 文件 | 用途 |
|------|------|
| `target/jfs-0.1.0-SNAPSHOT.jar` | 可直接运行的 shade jar（推荐） |
| `target/original-jfs-0.1.0-SNAPSHOT.jar` | 不含依赖的原始 jar |

不打包、直接从源码启动也可以：

```bash
mvn -q -DskipTests exec:java -Dexec.mainClass=com.levis9527.jfs.JfsServer
```

（上面这条需要本机已安装 `exec-maven-plugin` 或自行加插件；日常更推荐 `java -jar`。）

## 3. 启动服务

### 3.1 默认启动

```bash
java -jar target/jfs-0.1.0-SNAPSHOT.jar
```

默认行为：

- 监听 `0.0.0.0:8080`
- 数据目录 `./data`
- 2 个 volume，每个最大 1 GiB
- snowflake worker id = 1

启动成功时标准输出类似：

```text
jfs listening on 0.0.0.0:8080 (data=/abs/path/data volumes=2)
```

查看帮助：

```bash
java -jar target/jfs-0.1.0-SNAPSHOT.jar --help
```

### 3.2 命令行参数

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `-addr` | `:8080` | 监听地址。支持 `:8080`、`127.0.0.1:8080`、`8080` |
| `-data` | `./data` | 数据根目录（volume 文件 + `files.jsonl`） |
| `-volumes` | `2` | volume 数量，启动时按 `1..N` 创建/打开 |
| `-volume-size` | `1073741824`（1 GiB） | 单个 volume 最大字节数 |
| `-worker` | `1` | snowflake worker id，范围 `0..1023`，多实例时必须不同 |
| `-token` | 空 / 环境变量 `JFS_TOKEN` | 访问令牌。配置后才真正校验「需要鉴权」的文件 |
| `-h` / `--help` | — | 打印帮助后退出 |

示例：

```bash
# 指定端口与数据目录
java -jar target/jfs-0.1.0-SNAPSHOT.jar \
  -addr :8080 \
  -data ./data \
  -volumes 2 \
  -volume-size 1073741824 \
  -worker 1

# 仅本机可访问
java -jar target/jfs-0.1.0-SNAPSHOT.jar -addr 127.0.0.1:8080 -data /var/lib/jfs

# 开启按文件鉴权（推荐生产）
java -jar target/jfs-0.1.0-SNAPSHOT.jar -token 'change-me' -addr :8080 -data ./data
# 或：export JFS_TOKEN=change-me
```

`configs/jfs.conf` 只是参数备忘，**进程不会自动读取该文件**，所有选项都通过命令行传入。

### 3.3 后台运行

```bash
nohup java -jar target/jfs-0.1.0-SNAPSHOT.jar \
  -addr :8080 \
  -data ./data \
  > jfs.log 2>&1 &
echo $! > jfs.pid
```

停止：

```bash
kill "$(cat jfs.pid)"
# 或 Ctrl+C（前台进程会走 shutdown hook，关闭 HTTP 与文件句柄）
```

## 4. 启动后验证

```bash
# 健康检查
curl -s http://127.0.0.1:8080/ping
# {"ret":1,"msg":"pong"}

# 总览（文件数 / bucket / volume）
curl -s http://127.0.0.1:8080/overview

# 管理页（浏览器）
# http://127.0.0.1:8080/admin
```

浏览器访问根路径 `/` 且 `Accept` 含 `text/html` 时，会 302 到 `/admin`。

一键冒烟（需服务已启动）：

```bash
chmod +x examples/demo.sh
./examples/demo.sh http://127.0.0.1:8080
```

## 5. 管理控制台

打开：**http://127.0.0.1:8080/admin**

| 页面 | 功能 |
|------|------|
| 总览 | 文件数、bucket 数、对象体积、各 volume 占用 |
| 文件与元数据 | 按 bucket 过滤、搜索文件名/MIME/key、预览图片、改文件名与 MIME、下载、删除 |
| Volume | 每个 volume 的路径、已用/上限、文件数、只读状态 |
| 上传 | 选择本地文件，写入指定 bucket |

注意：

- `bucket + filename` 全局唯一，重复上传会返回冲突（HTTP 409）
- 删除是标记删除（needle flag），卷内空间不会立刻回收
- JSON 里的 `key` 以**字符串**返回，避免浏览器丢失 snowflake 精度

## 6. 数据目录布局

`-data` 指向的目录启动后大致为：

```text
data/
  files.jsonl          # directory 元数据（JSONL，追加写）
  volume_1/
    1.dat              # 小文件合并后的 superblock
    1.idx              # key -> offset 索引，用于快速恢复
  volume_2/
    2.dat
    2.idx
```

重启同一 `-data` 目录会自动加载索引与元数据，已有文件仍可读写。

**不要**在运行中手动改 `.dat` / `.idx` / `files.jsonl`。更换 `-volumes` 时：已有 `volume_N` 会被重新打开；新增 id 会创建空卷；若新的 volume 数量小于已有目录，多出来的旧卷本进程不会加载。

## 7. HTTP API

除下载接口外，JSON 统一包装：

```json
{ "ret": 1, "msg": "...", "data": ... }
```

`ret`：`1` 成功；`400` 参数错误；`401` 未授权；`404` 不存在；`409` 文件已存在；`500` 内部错误。

### 7.0 按文件鉴权

每个对象有 `auth` 字段（默认 `false` = 公开）：

| 对象 `auth` | 未带令牌读内容 | 带令牌读内容 |
|-------------|----------------|--------------|
| `false` 公开 | 允许 | 允许 |
| `true` 鉴权 | HTTP 401 | 允许 |

配置了 `-token` / `JFS_TOKEN` 之后：

- **上传、删除、改元数据、overview/stats** 一律要令牌
- **匿名 `/list`** 只返回公开文件；带令牌可看到私有文件
- `/ping`、`/auth`、`/admin` 静态页始终公开

未配置令牌时：`auth` 仍会写入元数据，但**不会强制校验**（启动日志会警告）。

传递令牌（任选其一）：

```bash
# Header
curl -H 'Authorization: Bearer change-me' ...
curl -H 'X-JFS-Token: change-me' ...

# Query（方便 <img> / 下载链接）
curl 'http://127.0.0.1:8080/sec/a.jpg?token=change-me'
```

上传时选择是否鉴权：

```bash
# 公开
curl -X PUT --data-binary @a.jpg -H 'Content-Type: image/jpeg' \
  -H 'Authorization: Bearer change-me' \
  'http://127.0.0.1:8080/img/a.jpg?auth=0'

# 私有（读也要令牌）
curl -X PUT --data-binary @secret.jpg -H 'Content-Type: image/jpeg' \
  -H 'Authorization: Bearer change-me' \
  -H 'X-JFS-Auth: 1' \
  http://127.0.0.1:8080/sec/secret.jpg

# 以后改成公开或私有
curl -X POST -H 'Authorization: Bearer change-me' \
  'http://127.0.0.1:8080/meta?bucket=sec&filename=secret.jpg&auth=0'
```

管理页左侧可填写令牌（保存在浏览器 localStorage），上传/详情里可勾选「读取需要鉴权」。

### 7.1 资源路径（推荐）

把对象看成 `/{bucket}/{filename}`：

```bash
# 上传（请求体即文件内容；Content-Type 会记为 mime）
curl -X PUT --data-binary @photo.jpg \
  -H 'Content-Type: image/jpeg' \
  http://127.0.0.1:8080/img/photo.jpg

# 下载
curl -o photo.jpg http://127.0.0.1:8080/img/photo.jpg

# 删除
curl -X DELETE http://127.0.0.1:8080/img/photo.jpg
```

上传成功示例：

```json
{
  "ret": 1,
  "data": {
    "bucket": "img",
    "filename": "photo.jpg",
    "key": "742926707702370304",
    "cookie": 2138081305,
    "vid": 1,
    "size": 12345,
    "url": "/img/photo.jpg"
  }
}
```

### 7.2 查询参数接口

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/ping` | 探活（无需令牌） |
| `GET` | `/auth` | `{enabled:true/false}` 是否配置了服务令牌 |
| `GET` | `/overview` | 文件/bucket/volume 汇总（需令牌，若已配置） |
| `GET` | `/stats` | 仅 volume 状态（需令牌，若已配置） |
| `GET` | `/buckets` | bucket 列表及占用（需令牌，若已配置） |
| `GET` | `/list?bucket=&q=` | 列文件；匿名只看公开文件 |
| `POST` | `/upload?bucket=&filename=&mime=&auth=0\|1` | 请求体为原始字节 |
| `GET` | `/get?bucket=&filename=` | 下载；`auth=true` 的对象需令牌 |
| `GET` | `/get?bucket=&filename=&meta=1` | 只返回元数据 JSON |
| `GET` | `/get?bucket=&filename=&download=1` | 带 `Content-Disposition` 附件下载 |
| `POST`/`DELETE` | `/del?bucket=&filename=` | 删除 |
| `POST`/`PUT` | `/meta?bucket=&filename=&newFilename=&mime=&auth=0\|1` | 改文件名 / MIME / 是否鉴权 |
| `GET` | `/admin` | Web 控制台 |

示例：

```bash
# 按 query 上传
curl -X POST --data-binary @photo.jpg \
  "http://127.0.0.1:8080/upload?bucket=img&filename=photo.jpg&mime=image/jpeg"

# 元数据
curl "http://127.0.0.1:8080/get?bucket=img&filename=photo.jpg&meta=1"

# 列表 / 搜索
curl "http://127.0.0.1:8080/list?bucket=img"
curl "http://127.0.0.1:8080/list?q=photo"

# 重命名并改 MIME
curl -X POST "http://127.0.0.1:8080/meta?bucket=img&filename=photo.jpg&newFilename=cover.jpg&mime=image/jpeg"

# 删除
curl -X POST "http://127.0.0.1:8080/del?bucket=img&filename=cover.jpg"
```

## 8. 元数据字段

每个对象在 directory 中保存：

| 字段 | 含义 |
|------|------|
| `bucket` | 逻辑桶，类似命名空间 |
| `filename` | 桶内文件名 |
| `mime` | Content-Type |
| `key` | 全局唯一 needle id（snowflake） |
| `cookie` | 读 store 时的校验值 |
| `vid` | 所在 volume id |
| `size` | 原始字节数 |
| `created` | Unix 秒 |
| `deleted` | 删除标记（列表接口不会返回已删对象） |
| `auth` | `true` 表示读取需要令牌；`false` 公开 |

物理数据在对应 volume 的 `.dat` 中，按 needle 追加写入。

## 9. 常见问题

**端口被占用**  
换 `-addr`，例如 `-addr :18080`。

**`file already exists`**  
同一 `bucket/filename` 不可覆盖，先删除或换文件名。

**重启后文件不见了**  
确认仍使用同一个 `-data`。默认是进程当前工作目录下的 `./data`。

**`no writable volume`**  
所有 volume 达到 `-volume-size`，或都被标记只读。增大 `-volume-size` / `-volumes` 后，**新 volume 才能扩容**；已有 `.dat` 不会因为改参数自动变大上限（上限是进程启动参数，但已写入的数据仍在原文件中）。

**worker id**  
单机保持默认即可。若以后拆多进程写同一逻辑集群，不同进程使用不同 `-worker`，避免 key 冲突。

**`unauthorized` / HTTP 401**  
配置了 `-token` 后，管理接口和 `auth=true` 的文件读取都要带令牌。公开文件（`auth=false`）仍可匿名下载。

**未配置 `-token` 时私有标记不生效**  
`auth=true` 会写进元数据，但没有服务令牌就无法校验，启动日志会提示。

**上传大小**  
当前 HTTP 层会把请求体读入内存，适合图片等小文件，不适合超大对象。

## 10. 开发与测试

```bash
mvn test
```

主要用例覆盖 needle 编解码、volume 读写恢复、directory 元数据持久化/重命名、以及 `/admin` 与 HTTP API。
