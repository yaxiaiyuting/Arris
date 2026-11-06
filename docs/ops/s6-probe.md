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
