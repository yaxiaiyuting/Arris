# v3.8.0 · 实验室真机记录（PLC110 / Android 16 / API 37）

日期：2026-10-30 00:20–00:35（Asia/Shanghai）。包：`v3.8.0-gpl`（versionCode 120，minSdk 29）。
用途：识曲采集链路与指纹稳定性的一次真实测量，作为后续 Provider 接入的前置证据。

## 1. 一起听自测（纯逻辑，真机输出）

```
协议版本=1
SetQueue(a,b) → seq=1 ok
Play(0) → seq=2 ok
成员操作被拒 → seq=3 rejected(NotHost)
重复序号被拒 → seq=2 rejected(Stale)
Next → seq=3 ok
当前曲=夜曲 下标=1
时钟 offset=9980ms rtt=40ms
2 秒后的目标位置=11980ms（公式不再减单程延迟）
漂移 30ms → Ignore
漂移 150ms → MicroAdjust(rate=1.01)
漂移 900ms → Seek
```

结论：三条不变量（房主权威 / seq 严格递增 / stamp 配位置）在真机上按预期工作。

## 2. 采集音源：`UNPROCESSED` 在这台机器上等于死路

同一次采集对三个源各探 1.5 秒：

| 音源 | probe RMS | 判断 |
|---|---|---|
| `MIC` | **0.0474** | 可用（-26 dBFS） |
| `VOICE_RECOGNITION` | 0.0271 | 可用但更弱 |
| `UNPROCESSED` | 0.0013 | ≈ 静音（-58 dBFS） |

**教训**：`AudioRecord` 初始化成功 ≠ 能录到声音。必须用 RMS 验收，并且**先探测再录满**
（逐个"录满再判"会白等 6 秒/源）。当前实现：3×1.5 秒探测选最响 → 用该源录满。

## 3. 指纹稳定性（真实音乐，外放 → 手机麦）

一次 8 秒录音，切 A=0–6s、B=2–8s（**同一段音频**，只差 2 秒偏移）：

```
A peaks=540 hashes=11331
B peaks=540 hashes=11615
自重合率=0.578   理论上限=0.65   归一化稳定度≈0.89
```

**关键口径**：A/B 只有 4/6 的帧重叠，所以理论最大重合率不是 1.0 而是 ~0.65。
不归一化会得出"0.58 不稳"的错误结论；归一化后 ≈0.89，说明峰值选取与组合哈希在真实音乐上是稳的。

## 4. 过程中修掉的三个 bug（都由真机数据暴露）

1. **实验室页闪退**：`LabScreen` 被嵌进关于页的可滚动 `Column` → `IllegalStateException:
   Vertically scrollable component was measured with an infinity maximum height constraints`。
   修法：移到最外层 `Box` 作为覆盖层。
2. **自测脚本自身写错**：`重复序号` 用了 `seq=3` 而当时 `state.seq=2`，于是它不是"重复"而是被应用，
   还顺手把播放暂停，导致目标位置显示 0ms。修法：用 `seq=2`。
3. **峰值选取"每帧每带必出一峰"**：静音时也固定 84 帧 × 4 带 = 336 个峰，重合率全靠噪声碰运气。
   修法：全频段局部极大（±3 bin）+ 相对全帧均值 2.5 倍 + 每帧最多 6 个。

## 5. 未验证 / 下一步

- `amp.shazam.com` 真实识别请求：**仍未发**（协议未验证，见调研报告 §1 的私钥与许可问题）。
- 内部音频（`AudioPlaybackCapture`）需 `MediaProjection` 授权，本轮未测。
- 识别结果 → 网易云/QQ 双源匹配（`CandidateRanker` 已就绪且有 8 个 JVM 用例），待 Provider 接通后接真数据。

---

## 6. 识曲端点：两轮复现的结论（2026-10-30 01:50）

### 6.1 端点已确认可用

`https://music.163.com/eapi/music/audio/match?_nmclfl=1`（`interface*.music.163.com` 同样可用），
eapi 加密的 `params=<hex>`，响应是**裸 AES-ECB 密文**（不是 base64、不是 zlib）。
用本机脚本 + 手机上的 cookie 复现，三台主机都返回：

```json
{"data":{"type":0,"queryId":"467c74be-…","result":null,"mv":null …}}
```

### 6.2 `rawdata` **不是 PCM**，是 shazam_v2 签名

同一天做的体积对照（同一端点、同一 cookie、只改 `rawdata` 内容）：

| 输入 | rawdata（zlib+base64） | 服务端响应 |
|---|---|---|
| 3.4 s 16 kHz 正弦（极易压缩） | **2 KB** | ✅ 160 B 正常 JSON |
| 3.4 s 16 kHz 噪声（模拟真实音乐的熵） | ≈80–100 KB | ❌ **空 body** |
| 真机录音（3.4 s，麦克风） | ≈90 KB | ❌ 空 body（App 里显示"空响应"） |

结合两条旁证：
1. 逆向文章原话：「猜测 rawdata 就是录音的 Base64，尝试解码扔进 ffprobe，**但是失败了**」；
2. 请求体里有 `algorithmCode: "shazam_v2"`。

⇒ `rawdata` 是 **Shazam 签名（KB 级二进制）**，不是原始波形。

### 6.3 因此现在的状态

- 采集 ✅、eapi 加密 ✅、端点 ✅、AES 解密 ✅、`data.result` 解析 ✅
- **唯一缺口**：用 PCM 生成 Shazam 兼容签名

### 6.4 下一步（需要实现的东西）

把 SongRec（GPL-3.0+）的 `algorithm.rs` + `signature_format.rs` 移植成 Kotlin：
FFT 2048 / hop 128、6 个频带的峰值选取、签名编码（magic `0xcafe2580` / `0x94119c00`、
`fft_pass_number` 差值编码、CRC32）。本机有 Rust 工具链，可先用 SongRec 生成参考签名，
再让 Kotlin 实现逐字节对齐（黄金文件测试）。

## 7. 签名移植 + 真机端到端（2026-10-30 02:04，PLC110）

### 7.1 载荷形态最终确定

| 发送内容 | rawdata | 服务端响应 |
|---|---|---|
| 3.4 s 麦克风 PCM | ≈120 KB | 空 body |
| **Shazam 签名**（本仓库生成） | **1.9–2.8 KB** | **HTTP 200 + 完整 JSON** |

真机 logcat：

```
NeteaseRecog: signature=1508B rawdata=1968B pcm=108800B
NeteaseRecog: audio/match HTTP 200 bytes=160
              plain={"data":{"type":0,"queryId":"2fec6bce-…","result":null,
                     "mv":null,"moduleList":null,"noMatchReason":10},"code":200,"message":""}
```

`result:null` 是"没听出来"（当时没放音乐），`noMatchReason=10` 会一并显示在实验室里。

### 7.2 签名移植的等价性证据

`ShazamSignature.kt` 移植自 SongRec `algorithm.rs` + `signature_format.rs`。
黄金文件由 Rust 原实现产出（`clip.f32` 3 秒合成音频 → `clip.sig` 2312 字节），
测试断言（`ShazamSignatureGoldenTest`）：

- 签名长度一致（2312）
- 头部逐字节一致（跳过 CRC 的 4..7）
- **每频带峰数一致**
- 载荷差异 ≤ 2 字节 —— 实测 1 字节：某个峰的 `fft_pass` 差 1，来源是 rustfft 与朴素 FFT
  的舍入边界（400+ 个峰里 1 个），服务端对时间轴有容差，不影响识别

### 7.3 一起听：真 socket 往返

`RoomRelayTest`（JVM，3 项）用 localhost 真 ServerSocket/Socket：
房主广播快照 → 成员收到且字段逐一相等；ping/pong 时钟偏移在本地回环接近 0；行协议编解码不丢字段。
实验室 ⑥ 按钮在设备上跑同一条链路（创建房间 → 成员连接 → 收快照 → 算时钟）。
