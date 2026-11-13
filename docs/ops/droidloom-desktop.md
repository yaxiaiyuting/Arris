# 把 Arris 当桌面播放器用（Droidloom + KWin）

> 目的不是测试，是**日常听歌**：用自己的播放器替掉 SPlayer 这类第三方，顺带把真实使用里
> 的问题逼出来。环境准备见 [droidloom-nvidia.md](../ops/droidloom-nvidia.md)。

## 已经能用的部分

| 能力 | 状态 | 说明 |
|---|---|---|
| 桌面入口 | ✅ | Droidloom 自动生成 `~/.local/share/applications/droidloom-*.desktop`，`Name=Arris`，应用菜单里直接点 |
| 窗口标题 | ✅ | 打过补丁：从 `~/.config/droidloom/titles.conf` 取显示名，KWin 现在显示 **`Arris`**（原来显示包名） |
| 窗口尺寸 | ✅ | desktop 模式记住每个应用的尺寸；也可 `--resolution 480x900` 指定初始值 |
| 拖动 / 缩放 | ✅ | 服务端装饰（标题栏 + 边框），Windows 式拖拽 |
| 滚轮 | ✅ | presenter 把 wheel 合成「手指上拖」手势（60 逻辑 px/tick） |
| 媒体键通路 | ✅ | `adb -s 10.177.0.2:5555 shell input keyevent 85` 能到 Arris 的 MediaSession（`dumpsys media_session` 里 `active=true`） |
| 音频 | ✅ 链路通 | `droidloom-audio.service` 起来后日志有 `Playback 48000/2 PCM16, four shared buffers`；实际听感要登录后放歌确认 |
| 剪贴板 / 通知 | ✅ | Android 剪贴板与桌面双向；Android 通知成为桌面通知 |

## 两个小脚本

```bash
# 启动（会自动拉起运行时；可选初始尺寸）
~/bin/arris-dl
~/bin/arris-dl 480x900
~/bin/arris-dl stop          # 停掉整个 Android 运行时省内存

# 媒体键：从宿主把按键送进 Android
~/bin/arris-keys play|next|prev|stop|volup|voldown|mute|seek+|seek-
```

源码也放在仓库里：`docs/ops/droidloom/arris-dl`、`arris-keys`。

**注意 `--component` 不能省**：只给包名的形式在这台机器上 10/10 返回 `Activity start failed: -91`。

## 媒体键接进键盘（KDE）

宿主媒体键不会自动进 Android。两个办法，选一个：

1. **KDE 全局快捷键**（简单）：系统设置 → 快捷键 → 添加命令，
   把 `XF86AudioPlay` / `XF86AudioNext` / `XF86AudioPrev` 分别绑到
   `~/bin/arris-keys play` / `next` / `prev`。播放/暂停键通常已被别的播放器占用，
   记得先把 SPlayer 的那条解绑。
2. **keyd / evsieve 层面重映射**（彻底，需要 root）：把对应键码直接改写成调用脚本，
   好处是不受桌面快捷键冲突影响。

## 窗口标题映射

`~/.config/droidloom/titles.conf`，一行一条 `包名=显示名`，`#` 注释：

```ini
com.takahashirinta.ncrust=Arris
com.android.settings=设置
com.android.documentsui=文件
```

改完 `systemctl --user restart droidloom.service` 生效；没列的包回落显示包名。
示例文件在 `docs/ops/droidloom/titles.conf.example`，补丁在
`/home/duanjb666/droidloom-nvidia/patches/presenter-window-title.patch`。

## 还不够「日常」的地方（按优先级）

| 缺口 | 影响 | 可行做法 |
|---|---|---|
| **没有托盘 / 全局媒体控制** | 窗口收起来就看不到在放什么 | Android 通知已镜像到桌面，先靠通知控制；想更好要桌面侧小部件读 MediaSession（Droidloom 目前没暴露） |
| 媒体键要手工绑 | 装完不能直接按 | 见上一节 |
| 窗口置顶 / 迷你模式 | 想要「一直在角落放」 | 现在只能靠 KWin 的规则（窗口规则里设 Keep above），Droidloom 没有专门 API |
| 首启要扫码登录 | 一次性 | 在 Droidloom 窗口里直接扫码；二维码渲染正常 |
| Android 里没有键盘输入法 | 搜索框输入靠宿主输入法（`zwp_text_input_v3` 已接，实测可输入） | — |
| `screencap` 拿不到画面 | 自动化截图只能宿主侧 | cell 的显示带 `FLAG_SECURE`，用 `spectre`/宿侧截图或 `uiautomator dump` |

## 一个提醒

Droidloom 是**早期预览**，我们为它打了补丁才在这台 NVIDIA + KWin 的机器上稳定
（power-manager 全局可选、按 allocation 逐级选 render target、标题映射；另有服务端装饰与
滚轮轴两条顺带修的）。升级它的包会覆盖 `/usr/bin/droidloom-wayland`，届时补丁要重打一遍 ——
见 `docs/ops/droidloom-nvidia.md` 的第 6 节。

补丁现在的落点（2026-11-13）：

- 上游 `main` 已到 release 34（`69956ed`），**仍未合入**我们的改动，`main.rs:3325` 的
  `zwlr_output_power_manager_v1` 还是硬 `bind`，KWin 上 stock 包照旧起不来。
- 5 个提交已重基到 release 34，分支 `yaxiaiyuting/droidloom@nvidia-kwin-presentation-v2`。
  **用分支，别用老的 `.patch`**：`git rebase --onto` 干净无冲突，而按 11-10 那套 mailbox patch
  直接 `git apply` 会在 3/4、4/4 撞上下文漂移，手合出来的东西才可能把 presenter 弄挂。
- 本机现在装的就是重基后的构建（`rustc 1.99.1`，`cargo build --release`，sha256 `e9fa1f94d711e72a…`，
  备份 `/usr/bin/droidloom-wayland.v1` 与 `.orig`）。
