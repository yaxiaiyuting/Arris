# v3.2.1 · 真机验证（SM-G9209 / Android 7.0）

> 设备：SM-G9209（zerofltctc）· Android 7.0（API 24）· arm64-v8a · Magisk root
> 被测包：`app/build/outputs/apk/release/app-release.apk` = `dist/Ncrust-v3.2.1-gpl-release.apk`
> （`versionCode 56` / `versionName 3.2.1-gpl`，`aapt2 dump badging` 实测）
> 采集脚本与原始产物：`.scratch/v321/`

---

## 1. B站扫码登录：二维码**已能正常生成并显示**（P0 验收）

**步骤**：冷启动 → 用户 tab → 账号与登录 → 「扫码登录 B 站」→ 观察二维码区。

**证据 1 · 截图**：`.scratch/v321/bili-qr-after.png`
（二维码清晰可见，下方文案是「请用哔哩哔哩客户端扫码」，而不是「二维码已失效，请刷新」）

**证据 2 · 应用日志**（`adb logcat -s BiliQrLogin:I`，时间戳为设备时间）：

```
20:50:33.965 BiliQrLogin: state: IDLE -> LOADING
20:51:34.374 BiliQrLogin: prepare: 二维码已申请（url 长度=131，key 长度=32）
20:51:34.374 BiliQrLogin: state: LOADING -> WAITING
20:51:34.809 BiliQrLoginDialog: 二维码已渲染，开始有界轮询（TTL 180s）
20:51:34.809 BiliQrLogin: poll: 开始有界轮询（间隔 2000ms / 上限 90 次 / TTL 180s）
```

这两条日志的**顺序**正是本次修复的判据：
`二维码已渲染` **先于** `开始有界轮询` —— v3.2.0 里这两步是反的（位图要等轮询到终态才生成）。

**证据 3 · UI 层级**（`uiautomator dump`）：对话框里**没有**任何「已失效」文案节点，
状态行是「请用哔哩哔哩客户端扫码」。

### 1.1 顺带实测到的一个**新事实**（与老版本现象直接相关）

那条 `generate` 请求在这台设备上**首次耗时约 60 秒**（20:50:33 → 20:51:34 才拿到 key）。
这一点解释了用户报告的另一半现象：

| 版本 | 生成慢/失败时二维码区显示 | 结论 |
|---|---|---|
| v3.2.0 | `else` 分支只分 `LOADING` 与"其它" ⇒ 显示 **「二维码已失效，请刷新」** | 用户看到的正是这一句 |
| v3.2.1 | `IDLE/LOADING` → 「加载中…」；`EXPIRED` → 「已失效，请刷新」；`FAILED` → **「二维码获取失败，请重试」** | 三档分开，不再把"没生成出来"说成"过期" |

> 未验证：**扫码 → 轮询 → 登录成功**这一段需要真实 B站账号扫码，本轮**没有**做。
> 覆盖它的是 `BiliAuthQrLoginTest` 的 27 个 JVM 用例（状态序列、成功凭据、幂等、取消、有界轮询）
> 与 `BiliQrPanelTest` 的 6 个文案分档用例 —— 都是**逻辑层**证据，不等于真机扫码成功。

## 2. 10 次冷启动无崩溃（P0 验收）

脚本：`.scratch/v321/coldstart10.sh`；每一轮：`logcat -c` → `am force-stop` → `am start` →
起播（媒体键）→ 回首页 → 点列表第 3 行（**换队列 → 重组**，正是崩溃报告里的触发路径）→ 观察 12 秒。

```bash
#!/bin/bash
for i in $(seq 1 10); do
  adb logcat -c; adb logcat -b all -v threadtime > run$i.txt &
  adb shell am force-stop com.takahashirinta.ncrust; sleep 2
  adb shell am start -n com.takahashirinta.ncrust/.MainActivity; sleep 8
  adb shell input keyevent 126; sleep 3
  adb shell input tap 180 2448          # 回首页 tab
  ...（点列表第 3 行）
  sleep 12; 检查 "FATAL EXCEPTION" / "invalid weight"
done
```

**结果**：见 §2.1（由脚本写入 `.scratch/v321/coldstart/summary.txt`）。
判定：**10 轮全部 `ok`、`invalid weight` 命中数 0**。

> 这台设备在 v3.2.0 上的历史复现率约 2/5（崩溃报告）；若真实复现率就是 40%，
> 10 轮全绿的概率约 **0.6%** —— 所以这是一条**较强**的证据，但不是数学证明。
> 与 C 项（JIT 对照实验）的结论一起读：**本轮 12 次复现尝试都没能复现**，
> 所以「崩溃不再出现」这句话的强度到此为止，不做过度声明。

### 2.1 逐轮记录

| 轮次 | 结果 | `invalid weight` 命中 | 进程 pid（证明真的冷启） | 点击落点 |
|---|---|---|---|---|
| 1 | ✅ ok | 0 | `19661` | `720 1703` |
| 2 | ✅ ok | 0 | `19901` | `720 1703` |
| 3 | ✅ ok | 0 | `20134` | `720 1703` |
| 4 | ✅ ok | 0 | `20380` | `720 1703` |
| 5 | ✅ ok | 0 | `20637` | `720 1703` |
| 6 | ✅ ok | 0 | `20870` | `720 1703` |
| 7 | ✅ ok | 0 | `21108` | `720 1703` |
| 8 | ✅ ok | 0 | `21349` | `720 1703` |
| 9 | ✅ ok | 0 | `21725` | `720 1703` |
| 10 | ✅ ok | 0 | `22022` | `720 1703` |

**汇总：10/10 无崩溃，`invalid weight` 命中 0 次。** 每一轮的 pid 都不同 ⇒ 确实是冷启动（不是复用上一个进程）。原始 logcat 每轮一份：`.scratch/v321/coldstart/run*.txt`。

## 3. 行为等价的真机 A/B 快照

见 [EQUIVALENCE.md](EQUIVALENCE.md)：6 个形态的 `uiautomator` 层级逐节点比对。

## 4. 设备最终状态（交付时）

| 项 | 值 |
|---|---|
| 安装包 | `com.takahashirinta.ncrust` **3.2.1-gpl / versionCode 56**（release 签名包） |
| 覆盖安装 | `adb install -r -d`（**保留**登录态与队列，未清数据） |
| `wm size` / `wm density` | 无 override（采集平板形态时的临时覆盖已复位并自证） |
| 待办给用户 | 用自己的 B站账号扫码验证登录成功那一段 |
