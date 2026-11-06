# 国内探针：Samsung S6（G9209）当服务器用

> 2026-11-06 落地。设备跑 **Debian 12 (bookworm) / aarch64**，不是 Android。
> SSH：`root@192.168.5.34`（公钥已装）；公网只有 **IPv6**（`2409:8a7c:2453:1691::/64`，前缀会变）。

## 为什么需要它

这个项目的「慢 / 卡 0% / 打不开」只在**中国大陆家宽**上复现，主机在伦敦、模拟器在机房，
都测不到真实体验。这台手机就是一个固定的国内观测点，顺便把闲置设备用起来。

## 已做的系统调整（省电/省内存，全部可逆）

| 项 | 命令 | 效果 |
|---|---|---|
| 关桌面 | `systemctl set-default multi-user.target` + `disable --now gdm` | 已用内存 980MB → **254MB** |
| 关无用服务 | `disable --now avahi-daemon bluetooth g9209-bluetooth colord upower udisks2 accounts-daemon serial-getty@ttyGS0` | 运行 unit 从 22 个降到 14 个 |
| 停自动升级 | `mask --now apt-daily{,-upgrade}.{service,timer}` | 避免半夜拉包抢带宽 |
| **熄屏** | `arris-screen-off.service`：背光写 0 + `fb0/blank=1` + 关光标闪烁 | 面板不发光，CPU/网络/SSH 照常 |
| 开机校时 | `arris-clock-fix.service`：NTP 没同步就用 HTTP `Date` 兜底 | 这台**没有 RTC**，不做这步会所有 HTTPS 报 `certificate is not yet valid` |

保留：`ssh` `NetworkManager` `wpa_supplicant` `systemd-resolved` `systemd-timesyncd` `systemd-networkd` `polkit` `dbus` `udevd` `journald` `logind` + `g9209-cpu-idle-v2` / `g9209-powerkey`（设备自带）。

## 探针本身

- 代码：`/srv/arris-probe/probe.py`（纯 python3 标准库，无第三方依赖）
- 定时：`arris-probe.timer` → 开机 90s 后首跑，之后每 **5 分钟**
- 输出：`/srv/arris-probe/data/<UTC>.json`（保留 7 天）+ `latest.json`
- 读取：`arris-probe-serve.service` → `http://[<设备v6>]:8080/latest.json`（只读）
- 测什么：站点 `/` 与 `/api/latest` 的 TTFB、APK 1MB 分段 + 4MB 连续吞吐、
  Gitee / ghproxy 镜像（含 404 死链判定）、清华源 4MB 分片作为**家宽基线**

```bash
ssh root@192.168.5.34 'cd /srv/arris-probe && python3 probe.py'   # 手动跑一次
ssh root@192.168.5.34 'journalctl -u arris-probe.service -n 5 -o cat'  # 看历史摘要
```

## 2026-11-06 首轮实测（CMCC 家宽，Wi-Fi 520Mbps 链路）

| 项 | 结果 |
|---|---|
| 家宽基线（清华源 4MB） | **3391 KB/s** ⇒ 线路本身没问题 |
| 官网 `/` | TTFB **4193ms**，13 KB/s |
| `/api/latest` | TTFB **5231ms** |
| `/get/latest.apk` 1MB 分段 | 59 KB/s（另一次 81 KB/s） |
| `/get/latest.apk` 4MB 连续 | 690 KB/s（另一次 304 KB/s）⇒ **波动极大** |
| Gitee 镜像 | **404**（站点已到 v4.0.14，Gitee 上只有到 4.0.12/3.7.32） |
| ghproxy 镜像 | 32 KB/s / 3377ms |

**两个待办**：
1. 官网 APK 吞吐比家宽基线差一个数量级，且波动大 —— 查 worker 的 `serveRangedAsset`
   （`run_worker_first = true` + 自实现 206）是否在拖速度。
2. **Gitee 上的发行版落后主线两版** ⇒ 客户端 `UpdateMirrors` 拼出的 Gitee 地址对最新版是 404。
   要么每次发版都同步 Gitee 附件，要么在客户端识别 404 后跳过该源（现在会浪费一次探测）。

## 还没做（候选）

- **IPv6 直连镜像**：家宽只有公网 v6，若路由器放行入站 80/443，可跑 Caddy + DDNS（AAAA）
  做“国内下载加速”，客户端加一条源参与分段轮转。上行带宽尚未测。
- **一起听中继**：现在是 CF Worker + Durable Objects，搬不过来；要跑得重写轻量 WS 中继。
- **备份目标**：`rsync dist/ → /srv/arris-dist`（剩 21GB），价值一般。
- **不要做**：Gradle 构建（2.9GB RAM / 无 JDK / aarch64）。

## 追加（2026-11-06 晚）：三件事都做了

### ① Gitee 发行版补齐 + 自动核对

新增 `tools/gitee-release-sync`：

```bash
tools/gitee-release-sync --missing     # 扫 GitHub 最近 10 个正式版，补齐 Gitee 缺的
tools/gitee-release-sync v4.0.14-gpl   # 单个
tools/gitee-release-sync --verify      # 逐版核对：资产名 + 字节数必须与 GitHub 完全一致
```

补齐了 `v4.0.13` / `v4.0.14` / `v3.7.32` / `v3.7.33`，并把 `v3.7.33` 上命名错误的附件
（Gitee 是 `Arris-v3.7.33-gpl.apk`，GitHub 是 `Arris-v3.7.33-lts-gpl.apk`）补成正名 ——
**名字不一致 = 客户端 `UpdateMirrors` 拼出来的地址静默 404**。

两个踩过的坑已写进脚本注释：
1. Gitee 的 `/releases/tags/<tag>` 对**不存在**的 tag 也回 `200`（body 是 `null`）⇒ 判定改成"列一遍再比对"
2. 资产名**必须从 GitHub API 取**，不要自己按 tag 拼（LTS 命名是 `Arris-v3.7.33-lts-gpl.apk`）

`--verify` 当前结果：10/10 全部一致。

### ② 官网慢在哪：CF 把 CMCC 路由到了**西雅图**

同一台设备、同一份 APK，三条路径实测：

| 路径 | 速率 | TTFB |
|---|---|---|
| `/get/<apk>`（worker 自实现 206） | 40 KB/s | 3295 ms |
| `/dl/<apk>`（静态直出） | 49 KB/s | 1619 ms |
| `speed.cloudflare.com`（CF 自家测速） | 596 KB/s | — |
| 清华源（家宽基线） | 3391 KB/s | — |

响应头给了答案：`cf-cache-status: HIT` 但 `cf-ray: …-SEA` —— **边缘在美西**。
所以不是 worker 的分块问题（两条路一样慢），是**免费版对中国移动的选路**；
CF 自家测速端点走了另一套 anycast，所以快 10 倍。

结论：靠调 worker 救不回来。要么付费 Argo，要么**绕开 CF**（下面 ③）。

### ③ 国内 IPv6 直连镜像（已起服务，等入站放行）

- nginx 1.22（`/etc/nginx/sites-available/arris-mirror`）监听 **8080**（v4+v6），
  root `/srv/arris-dist`，只放行 `*.apk` / `latest.apk` / `lts-latest.apk` / `/health`，
  原生支持 Range（这正是 CF 免费版经 worker 时丢掉的能力）
- 探针的只读端口挪到 **8081**（原来 8080 被它占了）
- 同步脚本 `tools/mirror-sync`：**从官网**拉当前包推到设备（不要用工作区 `relay/public/dl/`，
  那个目录可能落后于线上 —— 今天就踩过：本地是 4.0.13/3.7.32，线上已经是 4.0.14/3.7.33）
- 当前自测：`http://[2409:8a7c:2453:1691:7bf0:9691:e9f7:a09f]:8080/health` → 200，
  `/latest.apk` → 206

**还差两步（需要人来操作）**：
1. 路由器放行入站 IPv6 到该地址的 **80/443**（以及测试用 8080）—— 否则只能在局域网访问
2. 给一个 Cloudflare API Token（`Zone.DNS:Edit`，仅 `de5.net`）：用于 ① DDNS 更新 AAAA
   （ISP 前缀约 2 小时变一次）② Let's Encrypt DNS-01 签证书（HTTP-01 需要 80 入站，也可）

做完这两步，客户端 `UpdateMirrors` 加一条 `https://mirror.arris.de5.net` 就能参与分段轮转。

## 镜像已通（2026-11-06 晚，用户手机流量实测可达）

- AAAA `mirror.arris.de5.net`（DNS only，不走 CF 代理）
- Let's Encrypt 证书（DNS-01）；`certbot.timer` 自动续期
- nginx `443 ssl` 直发 `/srv/arris-dist`，原生 206；`8080` 保留排障
- `arris-ddns.timer` 每 5 分钟同步 AAAA（前缀约 2h 变一次）
- `tools/mirror-sync` 从官网拉当前包推过去（**不要**用工作区 `relay/public/dl/`）
- 客户端：`UpdateMirrors.MIRROR_BASE = https://mirror.arris.de5.net`，
  源顺序 本站 → Gitee → **自建镜像** → ghproxy（只对 IPv6 用户有效，连不上会超时跳过）

### 路由器放行要点（中兴固件）

`安全 → 本地服务控制 → 服务控制-IPv6 → 新建条目`：
名称任意、允许服务=是、接口=WAN、**服务类型勾 HTTPS（+PING）**。
「前缀」两个框必须填**完整 IPv6 地址**：`2409:8a7c:2453:1691::` + `64`
（只写 4 段会报「请输入正确的 IPv6 地址」）。

### 仍需注意

- 这台机器**没有 RTC**：时间退回 10 月会让所有 HTTPS 报 `certificate is not yet valid`，
  `arris-clock-fix.service` 只在开机跑一次，必要时手动 `systemctl start arris-clock-fix`
- 家宽上行未测；若以后发现镜像上行吃满，需要限速（nginx `limit_rate`）
