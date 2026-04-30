# ANI-RSS 使用手册

这份手册不是通用介绍，而是按当前仓库这套实际落地方案写的复刻文档。目标是把 `ani-rss + OpenList + qBittorrent + PeerBanHelper` 配成和我现在这台机器一样的工作方式：

- `ani-rss` 负责 RSS 轮询、自动下载、自动重命名、刮削、生成 `STRM`
- `qBittorrent` 负责实际 BT 下载和做种
- `OpenList` 不跑容器，直接跑在宿主机，负责：
  - 提供本地下载目录 WebDAV
  - 提供 `STRM` 库目录 WebDAV
  - 把视频上传到 `115`
- `PeerBanHelper` 负责 qB 的封禁和防吸血

这套方案的核心工作流是：

1. `ani-rss` 定时刷新订阅源
2. 发现新种子后推送给 `qBittorrent`
3. 下载完成后自动重命名
4. 自动刮削，生成 `nfo/poster/fanart`
5. 生成本地 `STRM`
6. 通过 `OpenList` 上传到 `115`
7. 上传完成后把本地 `STRM` 切换成云端 `STRM`
8. 上传期间只暂停做种任务，不影响 RSS、下载、刮削和后续任务

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
├── kodi-strm/               # STRM 媒体库输出目录
├── peer/data/               # PeerBanHelper 数据目录
└── OpenList/openlist/       # OpenList 程序和数据目录
```

### 1.4 OpenList 顶层存储结构

当前 `OpenList` 顶层实际有 3 个挂载点：

- `/115`
- `/local`
- `/strm`

对应作用：

- `/115`：云盘目标目录，`ani-rss` 上传视频到这里
- `/local`：把本地下载目录暴露成 WebDAV，供“本地 STRM”播放
- `/strm`：把本地 `STRM` 目录暴露成 WebDAV，供播放器扫库

## 2. 目录和命名约定

### 2.1 下载目录

当前实际模板：

```text
/downloads/${title}/Season ${season}
```

剧场版：

```text
/downloads/${title}
```

对应宿主机路径：

```text
/root/docker/qb/downloads
```

### 2.2 STRM 输出目录

当前实际模板：

```text
/kodi-strm/${weekName}/${title}/Season ${season}
```

对应宿主机路径：

```text
/root/docker/kodi-strm
```

这意味着每部在播番剧会按星期输出到：

```text
/root/docker/kodi-strm/星期二/和班上第二可爱的女孩成为朋友/Season 1
```

如果订阅被删除或归档，往季目录会放到：

```text
/root/docker/kodi-strm/往季/...
```

`ani-rss` 现在已经支持：

- 针对单个订阅源重刮它的当季 `STRM` 目录和往季归档目录
- 全量重刮全部订阅源的当季 `STRM` 目录和往季归档目录

## 3. OpenList 配置

### 3.1 部署方式

这里不是容器方案，直接在宿主机部署 `OpenList`。

当前本机结构：

```text
/root/docker/OpenList/openlist/openlist
/root/docker/OpenList/openlist/data/config.json
```

你可以直接用仓库外的脚本：

```bash
bash /root/docker/install-openlist-v4.sh
```

也可以自行安装，只要最终满足下面这些条件即可。

### 3.2 OpenList 服务要求

必须满足：

- 监听 `127.0.0.1:5244`
- 开启 WebDAV
- 能通过 API Key 调用上传接口
- 有 3 个可用存储：
  - `/115`
  - `/local`
  - `/strm`

当前 `config.json` 里可以确认到的关键项：

- 地址：`127.0.0.1`
- 端口：`5244`
- 数据库：`sqlite3`
- 临时目录：`data/temp`

### 3.3 存储建议

你至少需要配这 3 个存储：

1. `115`
   - 类型：你自己实际使用的 `115` 驱动
   - 用途：上传后的云端视频最终落这里

2. `local`
   - 类型：本地目录
   - 指向：`/root/docker/qb/downloads`
   - 用途：让本地视频能通过 WebDAV 被 `STRM` 引用

3. `strm`
   - 类型：本地目录
   - 指向：`/root/docker/kodi-strm`
   - 用途：播放器直接扫这个目录

### 3.4 WebDAV 地址约定

当前 `ani-rss` 里启用 `STRM` 的关键参数是：

- `strmBaseUrl = davs://你的域名/dav`
- `strmLocalWebDavPathPrefix = /local`
- `strmCloudWebDavPathPrefix = 留空`

这意味着：

- 本地 `STRM` 会写成：
  - `davs://你的域名/dav/local/...`
- 上传完成后会自动切换成：
  - `davs://你的域名/dav/115/动漫/...`

如果你不想用 `davs://`，也可以换成 `https://` 或别的可访问 WebDAV 基础地址，但要保证播放器能读。

## 4. qBittorrent 配置

### 4.1 容器部署

当前实际 `docker-compose` 片段如下：

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

### 4.2 qB 关键设置

当前这套方案实际依赖这些设置：

- 默认保存路径：`/downloads/`
- 临时目录：`/downloads/incomplete/`
- `Session\AddTorrentStopped=false`
- `Session\QueueingSystemEnabled=false`
- `WebUI\Port=15768`
- `WebUI\Username=你的用户名`
- `Session\GlobalMaxRatio=1`
- `Session\GlobalMaxSeedingMinutes=60`

注意：

- `ani-rss` 是通过 qB WebAPI 工作，不是靠 qB 自己的 RSS
- qB 的 RSS 自动下载和规则处理可以关掉
- 下载路径必须和 `ani-rss` 容器里看到的一致，也就是都用 `/downloads/...`

## 5. PeerBanHelper 配置

### 5.1 容器部署

当前实际 `docker-compose` 片段如下：

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

### 5.2 当前实际连接方式

当前 `PeerBanHelper` 配置里接的是本机 qB：

- 类型：`qbittorrent`
- 名称：`anime`
- 地址：`http://127.0.0.1:15768`
- 用户名/密码：qB WebUI 账号

`PeerBanHelper` WebUI：

- 监听：`0.0.0.0:9898`
- 访问前缀：`http://127.0.0.1:9898`

这部分对 `ani-rss` 不是硬依赖，但建议配上，否则公网环境的 qB 做种会很脏。

## 6. ani-rss 镜像构建

### 6.1 Dockerfile

当前仓库里的实际镜像构建方式是：

```dockerfile
FROM wushuo894/eclipse-temurin:25-jre-alpine

COPY docker/run.sh /run.sh
COPY docker/exec.sh /exec.sh
COPY ani-rss-application/target/ani-rss.jar /usr/app/ani-rss.jar
WORKDIR /usr/app
VOLUME /config
ENV PUID=0 PGID=0 UMASK=022
ENV SERVER_PORT=7789 CONFIG=/config TZ=Asia/Shanghai SWAGGER_ENABLED=false
EXPOSE $SERVER_PORT
RUN sed -i 's/\r//g' /*.sh && chmod +x /*.sh
CMD ["/exec.sh"]
```

也就是说这套仓库默认流程是：

1. 先用 Maven 把 jar 打出来
2. 再用 Dockerfile 打镜像

### 6.2 使用 Maven 镜像构建 jar

你要求保留 `maven` 镜像，这也是我现在这台机器的做法。直接用容器构建，不在宿主机装 Maven：

```bash
docker run --rm --network host \
  -e CI=true \
  -v /root/ani-rss:/workspace \
  -v /root/.m2:/root/.m2 \
  -w /workspace \
  maven:3.9-eclipse-temurin-25-libatomic \
  mvn -B -DskipTests package --file pom.xml
```

### 6.3 构建 ani-rss 镜像

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
      - /root/docker/kodi-strm:/kodi-strm
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

## 8. ani-rss 全局配置

下面只写这套方案里真正关键的项。

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

### 8.4 STRM

当前实际配置：

```text
STRM: 开启
播放基础地址: davs://你的域名/dav
本地文件系统前缀: /downloads
本地 WebDAV 路径前缀: /local
云端 WebDAV 路径前缀: 留空
STRM 输出位置: /kodi-strm/${weekName}/${title}/Season ${season}
```

实际效果：

- 下载完成后，先写本地 `STRM`
- 上传完成后，再把 `STRM` 切换成云端地址
- 如果某集已经是云端 `STRM`，后续本地重刷不会把它改回本地地址

### 8.5 上传

```text
自动上传: 开启
自动删除: 关闭
awaitStalledUP: 开启
```

这里要注意：

- 当前仓库代码已经被改过，不再把“上传”和“下载后其他流程”串成一个总队列
- 上传期间只暂停做种任务
- RSS 刷新、新下载、刮削、本地 `STRM` 生成不会被上传队列阻塞
- 上传队列清空时，会统一恢复之前暂停的做种任务

### 8.6 OpenList 上传通知

在 `通知设置` 里新增一条 `OPEN_LIST_UPLOAD`：

```text
类型: OPEN_LIST_UPLOAD
启用: 开启
触发状态: DOWNLOAD_END
Host: http://127.0.0.1:5244
ApiKey: 你的 OpenList API Key
上传位置: /115/动漫/${year}/${quarterName}/${title}/Season ${season}
上传位置(剧场版): /115/动漫/${year}/${quarterName}/${title}
上传完成后删除本地文件: 关闭
删除旧的同集文件: 按需开启
```

这条通知是这套方案的关键，它负责：

- 把视频传到 `/115`
- 成功后把本地 `STRM` 改成云端 `STRM`

### 8.7 消息通知

当前机器上还配了一条 `SERVER_CHAN`：

- 触发状态：`DOWNLOAD_END`、`ERROR`

这个不是核心功能，但建议至少保留一条错误通知，否则上传失败、刮削失败时你只能盯日志。

## 9. 登录与安全

当前这套方案实际使用：

- Web 登录用户名密码
- API Key
- `禁止多端登录 = 开启`
- `如果 IP 发生改变登录将失效 = 开启`

建议：

- README 里不要直接保留你自己的明文密码或 API Key
- 对外暴露时，至少把 `ani-rss`、`qB`、`OpenList` 放在反代后面
- 如果只本机访问，保持 `127.0.0.1` 监听更安全

## 10. 订阅添加建议

当前这套用法的推荐做法：

1. 先在 `ani-rss` 里创建订阅
2. 主 RSS 尽量用稳定源
3. 需要洗版时再加 `备用RSS`
4. 命名模板统一保持 `SxxExx`
5. `下载路径` 不要每个订阅随便改，尽量统一

这样做的原因：

- `STRM`
- 刮削元数据
- 上传目标路径
- 云端洗版替换

都依赖统一的目录和命名。

## 11. STRM 重刮接口

当前仓库已经有两类接口和前端入口。

### 11.1 全量重刮

```http
POST /api/scrapeStrmAll?force=true
```

作用：

- 遍历所有订阅
- 重刮每个订阅现存的：
  - 当季 `STRM` 目录
  - 往季归档目录

### 11.2 单订阅重刮

```http
POST /api/scrapeStrm?force=true
```

作用：

- 只重刮指定订阅的：
  - 当季 `STRM` 目录
  - 往季归档目录

前端入口：

- 全局按钮：`STRM刮削`
- 单订阅下拉：`STRM目录刮削`

## 12. 验证清单

配置完后，按这个顺序验证。

### 12.1 验证 OpenList

确认能访问：

```text
http://127.0.0.1:5244
```

并且根目录能看到：

- `/115`
- `/local`
- `/strm`

### 12.2 验证 qB

确认能访问：

```text
http://127.0.0.1:15768
```

并且下载目录是：

```text
/downloads
```

### 12.3 验证 ani-rss

确认能访问：

```text
http://127.0.0.1:7789
```

在 `下载设置` 里测试 qB 登录通过，在 `通知设置` 里测试 OpenList 上传配置无误。

### 12.4 验证整条链路

随便找一部新番做一次完整测试，确认以下 8 步都发生：

1. RSS 检测到新种子
2. qB 出现下载任务
3. 下载完成后文件被重命名
4. 下载目录出现 `nfo/poster/fanart`
5. `kodi-strm/星期X/...` 下出现本地 `STRM`
6. OpenList `/115/...` 下出现上传后的视频
7. 本地 `STRM` 内容切换成云端地址
8. 上传结束后暂停的做种任务恢复

## 13. 常见坑

### 13.1 下载目录映射不一致

如果 qB 看到的是 `/downloads`，`ani-rss` 看到的是别的路径，后续重命名、刮削、STRM、上传都会错。

### 13.2 OpenList 没有 `/local`

没有 `/local`，本地 `STRM` 会生成失败，或者生成后不能播。

### 13.3 OpenList 没有 `/strm`

没有 `/strm`，播放器没法直接扫这个库。

### 13.4 上传路径和 STRM 云端路径不一致

如果你手工写了 `strmCloudWebDavPathPrefix`，它必须和上传目标对上。否则上传完成后，`STRM` 会切成错误地址。

### 13.5 重命名模板不带 `SxxExx`

如果模板不保留 `S${seasonFormat}E${episodeFormat}` 或 `S${season}E${episode}`，很多后续能力都会出问题，包括：

- 刮削识别
- 旧集替换
- `STRM` 重刮
- 同集判断

## 14. 我当前实际使用的关键配置摘要

下面这份可以直接作为你自己的对照模板，敏感值请自行替换：

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
  strm: true
  strm_base_url: davs://你的域名/dav
  strm_local_path_prefix: /downloads
  strm_local_webdav_path_prefix: /local
  strm_output_path_template: /kodi-strm/${weekName}/${title}/Season ${season}
  upload: true

openlist:
  host: http://127.0.0.1:5244
  root_storages:
    - /115
    - /local
    - /strm

openlist_upload_notification:
  type: OPEN_LIST_UPLOAD
  status: DOWNLOAD_END
  path: /115/动漫/${year}/${quarterName}/${title}/Season ${season}
  ova_path: /115/动漫/${year}/${quarterName}/${title}

qbittorrent:
  host: http://127.0.0.1:15768
  save_path: /downloads
  temp_path: /downloads/incomplete

peerbanhelper:
  host: http://127.0.0.1:9898
  downloader_endpoint: http://127.0.0.1:15768
```

## 15. 总结

如果你想复刻的就是“我现在这套”而不是官方默认方案，核心只有 5 条：

1. `OpenList` 跑宿主机，不跑容器
2. `OpenList` 同时挂出 `/115`、`/local`、`/strm`
3. `qB` 和 `ani-rss` 共用同一套 `/downloads` 路径
4. `ani-rss` 开启 `STRM + 刮削 + OpenList 上传`
5. `STRM` 输出到 `/kodi-strm/${weekName}/${title}/Season ${season}`

按这 5 条去搭，整条链路就能跑成和当前仓库一致的模式。
