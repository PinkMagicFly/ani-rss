# ANI-RSS 使用手册

这份手册按当前服务器上的实际方案记录。`ani-rss` 负责 RSS 自动追番、下载、重命名、刮削、上传和通知。

核心工作流：

1. `ani-rss` 定时刷新订阅源
2. 发现新种子后推送给 `qBittorrent`
3. 下载完成后自动重命名
4. 自动刮削，生成 `nfo/poster/fanart`
5. 通过 `OpenList` 把视频上传到 `115`
6. 上传期间只暂停做种任务，不影响 RSS、下载、刮削和后续任务
7. 上传结束后恢复之前暂停的做种任务

## 1. 当前方案拓扑

### 1.1 组件版本

- `ani-rss`：当前仓库源码自编译镜像
- `qBittorrent`：`linuxserver/qbittorrent:5.1.4`
- `PeerBanHelper`：`ghostchu/peerbanhelper:v9.2.5`
- `OpenList`：宿主机部署，当前是 `v4.1.10`

### 1.2 实际端口

- `ani-rss`：`127.0.0.1:7789`
- `qB WebUI / API`：`127.0.0.1:15768`
- `OpenList`：`127.0.0.1:5244`
- `PeerBanHelper`：`127.0.0.1:9898`

### 1.3 实际目录

```text
/root/docker/
├── ani-rss-config/          # ani-rss 配置、缓存、日志、订阅数据
├── qb/
│   ├── config/              # qB 配置
│   └── downloads/           # 实际下载目录
├── peer/data/               # PeerBanHelper 数据目录
└── OpenList/openlist/       # OpenList 程序和数据目录
```

### 1.4 OpenList 顶层存储结构

当前 `OpenList` 至少需要这 2 个挂载点：

- `/115`：云盘目标目录，`ani-rss` 上传视频到这里
- `/local`：把本地下载目录暴露给 OpenList，供上传通知用 `fs/copy` 从本地复制到 `/115`

## 2. 目录和命名约定

### 2.1 下载目录

番剧下载模板：

```text
/downloads/${title}/Season ${season}
```

剧场版下载模板：

```text
/downloads/${title}
```

对应宿主机路径：

```text
/root/docker/qb/downloads
```

### 2.2 OpenList 上传目录

当前上传模板：

```text
/115/动漫/${year}/${quarterName}/${weekName}/${title}/Season ${season}
```

剧场版：

```text
/115/动漫/${year}/${quarterName}/${weekName}/${title}
```

## 3. OpenList 配置

OpenList 当前不跑容器，直接部署在宿主机。

当前本机结构：

```text
/root/docker/OpenList/openlist/openlist
/root/docker/OpenList/openlist/data/config.json
```

服务要求：

- 监听 `127.0.0.1:5244`
- 能通过 API Key 调用接口
- 有 `/115` 云盘存储
- 有 `/local` 本地目录存储，指向 `/root/docker/qb/downloads`

`/local` 很关键：OpenList 上传通知会把容器内的 `/downloads/...` 映射成 OpenList 内的 `/local/...`，然后调用 OpenList 的 `fs/copy` 把文件复制到 `/115/...`。

## 4. qBittorrent 配置

当前实际 `docker-compose` 片段：

```yaml
qBittorrent:
  image: linuxserver/qbittorrent:5.1.4
  container_name: qBittorrent
  environment:
    - PUID=0
    - PGID=0
    - WEBUI_PORT=15768
    - BT_PORT=34567
    - QB_USERNAME=你的用户名
    - QB_PASSWORD=你的密码
    - TZ=Asia/Shanghai
  volumes:
    - /root/docker/qb/config:/config
    - /root/docker/qb/downloads:/downloads
  restart: always
  network_mode: host
```

关键设置：

- 默认保存路径：`/downloads/`
- 临时目录：`/downloads/incomplete/`
- `Session\AddTorrentStopped=false`
- `Session\QueueingSystemEnabled=false`
- `WebUI\Port=15768`
- `Session\GlobalMaxRatio=1`
- `Session\GlobalMaxSeedingMinutes=60`

注意：`ani-rss` 通过 qB WebAPI 工作，不依赖 qB 自己的 RSS。

## 5. PeerBanHelper 配置

当前实际 `docker-compose` 片段：

```yaml
peerbanhelper:
  image: ghostchu/peerbanhelper:v9.2.5
  container_name: peerbanhelper
  restart: unless-stopped
  volumes:
    - ./peer/data:/app/data
  network_mode: host
  stop_grace_period: 30s
```

PeerBanHelper 接本机 qB：

- 类型：`qbittorrent`
- 名称：`anime`
- 地址：`http://127.0.0.1:15768`
- 用户名/密码：qB WebUI 账号

## 6. ani-rss 镜像构建

### 6.1 使用 Maven 镜像构建 jar

当前服务器已有 Maven 镜像：

```text
maven:3.9-eclipse-temurin-25-libatomic
```

构建命令：

```bash
docker run --rm --network host \
  -e CI=true \
  -v /root/ani-rss:/workspace \
  -v /root/.m2:/root/.m2 \
  -w /workspace \
  maven:3.9-eclipse-temurin-25-libatomic \
  mvn -B -DskipTests package --file pom.xml
```

### 6.2 构建 ani-rss 镜像

```bash
docker build -f /root/ani-rss/docker/Dockerfile \
  -t ani-rss:custom \
  /root/ani-rss
```

## 7. ani-rss、qB、PBH 的 compose

当前这台机器上实际跑的是 host 网络方案：

```yaml
version: "3"
services:
  ani-rss:
    image: ani-rss:custom
    container_name: ani-rss
    volumes:
      - /root/docker/ani-rss-config:/config
      - /root/docker/qb/downloads:/downloads
      - /root/docker/qb/downloads:/root/docker/qb/downloads
    restart: unless-stopped
    network_mode: host
    environment:
      - TZ=Asia/Shanghai
      - SERVER_ADDRESS=127.0.0.1
      - SERVER_PORT=7789
      - CONFIG=/config
      - SWAGGER_ENABLED=false
      - JAVA_TOOL_OPTIONS=-XX:+IgnoreUnrecognizedVMOptions -XX:+IdleTuningGcOnIdle -Djava.net.preferIPv4Stack=true -Djava.net.preferIPv6Addresses=false

  qBittorrent:
    image: linuxserver/qbittorrent:5.1.4
    container_name: qBittorrent
    environment:
      - PUID=0
      - PGID=0
      - WEBUI_PORT=15768
      - BT_PORT=34567
      - QB_USERNAME=你的用户名
      - QB_PASSWORD=你的密码
      - TZ=Asia/Shanghai
    volumes:
      - /root/docker/qb/config:/config
      - /root/docker/qb/downloads:/downloads
    restart: always
    network_mode: host

  peerbanhelper:
    image: ghostchu/peerbanhelper:v9.2.5
    container_name: peerbanhelper
    restart: unless-stopped
    volumes:
      - ./peer/data:/app/data
    network_mode: host
    stop_grace_period: 30s
```

启动：

```bash
docker compose -f /root/docker/docker-compose.yml up -d
```

## 8. ani-rss 关键配置

### 8.1 下载器

```text
下载工具: qBittorrent
地址: http://127.0.0.1:15768
qb保存路径: 关闭
保存位置: /downloads/${title}/Season ${season}
剧场版保存位置: /downloads/${title}
```

### 8.2 下载和重命名

```text
自动重命名: 开启
重命名间隔: 10 秒
重命名模板: [${subgroup}] ${title} S${seasonFormat}E${episodeFormat}
失败重试次数: 3
延迟下载: 0
同时下载限制: 0
```

### 8.3 RSS 和刮削

```text
RSS轮询间隔: 30 分钟
TMDB: 开启
TMDB语言: zh-CN
TMDB标题: 开启
自动刮削: 开启
只下载最新集: 开启
```

缺集检测只读取当前订阅种子目录下的 `.episode` 标记，并判断现有最小集到最大集之间是否连续；它不再用 RSS 条目推断缺集，也不会因为缺集检测主动下载旧集。

### 8.4 OpenList 上传通知

在 `通知设置` 里新增一条 `OPEN_LIST_UPLOAD`：

```text
类型: OPEN_LIST_UPLOAD
启用: 开启
触发状态: DOWNLOAD_END
Host: http://127.0.0.1:5244
ApiKey: 你的 OpenList API Key
上传位置: /115/动漫/${year}/${quarterName}/${weekName}/${title}/Season ${season}
上传位置(剧场版): /115/动漫/${year}/${quarterName}/${weekName}/${title}
本地路径前缀: /downloads
OpenList 本地挂载: /local
上传完成后删除本地文件: 关闭
删除旧的同集文件: 按需开启
```

这条通知负责把本地下载完成的视频从 `/local/...` 复制到 `/115/...`。

## 9. 登录与安全

当前这套方案实际使用：

- Web 登录用户名密码
- API Key
- `禁止多端登录 = 开启`
- `如果 IP 发生改变登录将失效 = 开启`

建议：

- README 里不要保留明文密码或 API Key
- 对外暴露时，至少把 `ani-rss`、`qB`、`OpenList` 放在反代后面
- 如果只本机访问，保持 `127.0.0.1` 监听更安全

## 10. 验证清单

### 10.1 验证 OpenList

确认能访问：

```text
http://127.0.0.1:5244
```

并且根目录能看到：

- `/115`
- `/local`

### 10.2 验证 qB

确认能访问：

```text
http://127.0.0.1:15768
```

并且下载目录是：

```text
/downloads
```

### 10.3 验证 ani-rss

确认能访问：

```text
http://127.0.0.1:7789
```

在 `下载设置` 里测试 qB 登录通过，在 `通知设置` 里测试 OpenList 上传配置无误。

### 10.4 验证整条链路

找一部新番做完整测试，确认：

1. RSS 检测到新种子
2. qB 出现下载任务
3. 下载完成后文件被重命名
4. 下载目录出现 `nfo/poster/fanart`
5. OpenList `/115/...` 下出现上传后的视频
6. 上传结束后暂停的做种任务恢复

## 11. 常见坑

### 11.1 下载目录映射不一致

如果 qB 看到的是 `/downloads`，`ani-rss` 看到的是别的路径，后续重命名、刮削、上传都会错。

### 11.2 OpenList 没有 `/local`

没有 `/local`，OpenList 上传通知无法把本地文件复制到 `/115`。

### 11.3 重命名模板不带 `SxxExx`

如果模板不保留 `S${seasonFormat}E${episodeFormat}` 或 `S${season}E${episode}`，很多后续能力都会出问题，包括：

- 刮削识别
- 旧集替换
- 同集判断

## 12. 关键配置摘要

```text
ani-rss:
  host: http://127.0.0.1:7789
  config_dir: /root/docker/ani-rss-config
  download_tool: qBittorrent
  download_tool_host: http://127.0.0.1:15768
  download_path_template: /downloads/${title}/Season ${season}
  rename_template: [${subgroup}] ${title} S${seasonFormat}E${episodeFormat}
  rss_sleep_minutes: 30
  rename_sleep_seconds: 10
  tmdb: true
  tmdb_language: zh-CN
  scrape: true
  upload: true

openlist:
  host: http://127.0.0.1:5244
  root_storages:
    - /115
    - /local

openlist_upload_notification:
  type: OPEN_LIST_UPLOAD
  status: DOWNLOAD_END
  path: /115/动漫/${year}/${quarterName}/${weekName}/${title}/Season ${season}
  ova_path: /115/动漫/${year}/${quarterName}/${weekName}/${title}
  local_path_prefix: /downloads
  local_openlist_path_prefix: /local

qbittorrent:
  host: http://127.0.0.1:15768
  save_path: /downloads
  temp_path: /downloads/incomplete

peerbanhelper:
  host: http://127.0.0.1:9898
  downloader_endpoint: http://127.0.0.1:15768
```

## 13. 总结

当前方案的核心只有 4 条：

1. `OpenList` 跑宿主机，不跑容器
2. `OpenList` 挂出 `/115` 和 `/local`
3. `qB` 和 `ani-rss` 共用同一套 `/downloads` 路径
4. `ani-rss` 开启刮削和 OpenList 上传
