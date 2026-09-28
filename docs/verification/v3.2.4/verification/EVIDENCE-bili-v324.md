# v3.2.4 · P0 真机证据（B 站播放）

> 设备：`emulator-5554` / `sdk_gphone64_x86_64` / **API 33** / 1080×2340 / 60.000004Hz。
> 真实网络（设备出口与 host 侧 `curl` 同一个公网 IP，实测 `nav_http=200`）。
> 探针：`app/src/androidTest/java/com/takahashirinta/ncrust/probe/BiliV324ProbeTest.kt`
> （**androidTest 源集，不进 release 产物**）。
> 原始 logcat 逐字留档：
> [`raw-*`](.) = **修复前**（v3.2.3 代码 + 本版探针），[`postfix-*`](.) = **修复后**。

## 1. 修复前（P0 复现）

`raw-logcat-…-probeProductionChainAgainstRealCdn.txt`：

```
PROBE-V324-CDN url=https://upos-sz-mirrorhw.bilivideo.com/ugaxcode/ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a?e=…
PROBE-V324-CDN host=upos-sz-mirrorhw.bilivideo.com needsReferer=true cacheKey=bili:ef0083e1cd13e73dcd20bfe3e672c21a-320k.m4a
PROBE-V324-CDN requestHeaders={Referer=https://www.bilibili.com/}          ← 只有 Referer，没有 UA
PROBE-V324-CDN variant=A_production_as_is -> FAIL InvalidResponseCodeException http=403 msg=Response code: 403
PROBE-V324-CDN variant=B_probe_manual_ua -> OK opened=100000 firstRead=4096
PROBE-V324-CDN variant=C_probe_manual_ua_no_referer -> FAIL InvalidResponseCodeException http=403
```

**A 与 B 的唯一差别是「工厂有没有 `setUserAgent`」** ⇒ 根因锁定在 UA，不是 Referer（C 臂证明 Referer 仍必需）。

`raw-logcat-…-probeDefaultRequestHeaders.txt`（本地 `ServerSocket` 俘获 media3 实际写的头）：

```
PROBE-V324-HEADERS| GET /probe.m4a HTTP/1.1
PROBE-V324-HEADERS| Range: bytes=0-1023
PROBE-V324-HEADERS| Accept-Encoding: identity
PROBE-V324-HEADERS| User-Agent: Dalvik/2.1.0 (Linux; U; Android 13; sdk_gphone64_x86_64 Build/TE1A.240213.009)
PROBE-V324-HEADERS| Host: 127.0.0.1:44101
PROBE-V324-HEADERS| Connection: Keep-Alive
PROBE-V324-HEADERS ua_contains_android=true
```

⇒ 平台默认 UA，**同时命中 CDN 黑名单里的 `dalvik` 与 `android`**。

`raw-logcat-…-probeRawUaMatrixFromDevice.txt`（设备出口、Referer 固定为 B 站、逐条换 UA）：

| 变体 | UA | HTTP |
|---|---|---|
| A | 不带 UA 头 | **403** |
| B | `Dalvik/2.1.0 (Linux; U; Android 13; probe)` | **403** |
| C | 桌面 Chrome UA | **206** |
| D | 桌面 Chrome UA，**去掉 Referer** | **403** |
| E | `ExoPlayerLib/1.5.0` | **206** |
| F | `Android` | **403** |

## 2. 修复后（同一台设备、同一条链路、同一批用例）

`postfix-logcat-…-probeProductionChainAgainstRealCdn.txt`：

```
PROBE-V324-CDN requestHeaders={Referer=https://www.bilibili.com/, User-Agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) …, Chrome/120.0.0.0 Safari/537.36}
PROBE-V324-CDN variant=A_production_as_is -> OK opened=100000 firstRead=4096     ← ★ 生产链路通了
PROBE-V324-CDN variant=B_probe_manual_ua -> OK opened=100000 firstRead=4096
PROBE-V324-CDN variant=C_probe_manual_ua_no_referer -> FAIL InvalidResponseCodeException http=403
```

**A 从 403 变成 206 + 真实字节**，而 C（去掉 Referer）**仍然 403** ⇒ 两道闸都在，且没有把任何一道拆掉。

`postfix-logcat-…-probeSourceRoutingDecision.txt`（路由决策，两个记录型假数据源，不发一个字节）：

```
PROBE-V324-ROUTE case=bili_whitelist_host plain=0 bili=1     ← 白名单 host 走 B 站分支
PROBE-V324-ROUTE case=netease            plain=1 bili=1     ← 网易云走普通分支
PROBE-V324-ROUTE case=qq                 plain=2 bili=1     ← QQ 走普通分支
PROBE-V324-ROUTE case=pcdn_before_mark   plain=3 bili=1     ← 未标记的第三方域名：普通分支
PROBE-V324-ROUTE case=pcdn_after_mark    plain=3 bili=2     ← markStream 之后：B 站分支
PROBE-V324-ROUTE bili_saw_referer=true bili_saw_ua=true     ← B 站分支两个头都带上
PROBE-V324-ROUTE plain_saw_referer=false plain_saw_ua=false ← 其他音源一个头都不加
```

`postfix-logcat-…-probeDefaultRequestHeaders.txt` 与修复前**逐字相同**（`Dalvik/…Android…`）——
这是**正确的**：那个用例打的是 `127.0.0.1`，不是 B 站媒体，必须走「逐字节不变」的普通分支。

## 3. 复现方式

```bash
ANDROID_SERIAL=emulator-5554 ./gw.sh :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.takahashirinta.ncrust.probe.BiliV324ProbeTest
# 输出：app/build/outputs/androidTest-results/connected/debug/<device>/logcat-*BiliV324ProbeTest-*.txt
```

## 4. 未覆盖（如实）

- **登录态**播放未测（无 B 站账号）。
- **多首歌 / 多档位**只覆盖了 `auid=39` 的 320K（`quality=2`）与一条视频 DASH 音轨（host 侧 curl）；
  取链参数的完备性由 host 侧矩阵覆盖（`evidence/audio-url-param-matrix.txt`）。
- **30 分钟以上的连续播放**未测（URL 过期跨越行为）。
