# 在 Droidloom（NVIDIA + KWin）里跑 Arris

> 结论：**能跑**。Arris 作为 KWin 里的一个 720×1200 窗口运行，零拷贝、可交互、滚轮与拖动可用。
> 环境：CachyOS（Arch 系）/ KWin 6.8 Wayland / NVIDIA RTX 4060 Laptop + AMD Rembrandt 核显。
> 这份文档记录**可复现的配置**，以及我们踩过的三个坑——其中一个是**我（脚本）自己造的**。

## 1. Droidloom 是什么

[denialwm/droidloom](https://github.com/denialwm/droidloom)：把 Android 用户空间（ART / Binder / Zygote /
system_server）直接跑在 Linux 内核的 namespace 里，**没有虚拟机**；每个 Android 应用是一个独立的
Wayland 窗口，DMA-BUF 零拷贝，剪贴板 / 通知互通。早期预览，GPL-3.0，只发 Arch 的 pacman 包
（**未签名**，HTTPS 分发）。

需要的内核能力：`CONFIG_ANDROID_BINDERFS=y`（CachyOS 内核自带）。

## 2. 安装与启动

```bash
# 官方安装脚本会加 pacman 仓库（SigLevel = Never）并 pacman -Syu
# 只想要包、不做全系统升级时，直接装官方缓存源里的两个包：
curl -L -o droidloom-image.pkg.tar.zst \
  https://github.com/denialwm/droidloom/releases/download/packages/droidloom-image-0.1.0-31-x86_64.pkg.tar.zst
curl -L -o droidloom-runtime.pkg.tar.zst \
  https://github.com/denialwm/droidloom/releases/download/packages/droidloom-runtime-0.1.0-31-x86_64.pkg.tar.zst
sudo pacman -U ./droidloom-image.pkg.tar.zst ./droidloom-runtime.pkg.tar.zst

droidloomctl start                  # 首次会写 /etc/droidloom、polkit 规则、Android 数据
droidloomctl install <apk>
droidloomctl launch --component com.takahashirinta.ncrust/com.takahashirinta.ncrust.MainActivity \
                    com.takahashirinta.ncrust
```

⚠️ **`--component` 不能省**。只给包名的形式在这里 10/10 失败（`Activity start failed: -91`），
Settings 偶尔成功，不可靠。

## 3. 三个坑（按发现顺序）

### 3.1 presenter 把 wlroots 专有全局当必需

`graphics/droidloom-wayland/src/main.rs` 里 `ZwlrOutputPowerManagerV1` 是硬 `?` 绑定，
KWin 不实现 `zwlr_output_power_manager_v1` → 启动即 `Wayland global bind failure`。
改法：改成 `Option`，缺它就跳过「按显示器电源状态暂停渲染」。

### 3.2 只认 linear 修饰符

`selected_format()` 只接受 `Modifier::Linear` 的 ARGB8888/XRGB8888。KWin 在 NVIDIA 上不广告
linear（NVIDIA 的 linear 是 `external_only=1`，被 KWin 的 `filterFormats()` 剔掉），于是
`NoRenderFormat`。

**真正的修法不是「放宽到任意修饰符」**（我第一次这么改，结果是 presenter 活下来了、一开窗口就
`GBM failure`）：要给 **Android 侧分配**与**给 KWin 的广告**两个不同的修饰符值 —— Android 用
minigbm 能分配的（`0x0300000000606010`，KIND=6 / COMPRESSION=0），KWin 用它能导入的。
并**硬白名单挡掉 `COMPRESSION=1`**：KWin 广告、gbm 也能分配，但 Android 侧的 host Vulkan 不认，
会把整个 `droidloom-gpu-server` abort 掉。

### 3.3 `GBM_BACKEND=dri`（**这条是我留下的**）

排查阶段我在 systemd user manager 里跑过
`systemctl --user set-environment GBM_BACKEND=dri`，它**不在磁盘任何文件里**，重登录也不会消失。
带着它，Mesa 走通用 dri 后端，连 renderD128 都认不出 NVIDIA：

```
pci id for fd 3: 10de:28a0, driver (null)
CREATE_DUMB failed: Permission denied
```

所以「只把 render node 换成 128」仍然是失败的，必须**同时 unset**。
现在钉在 drop-in 里，防止复活：

```ini
# ~/.config/systemd/user/droidloom.service.d/90-render-node.conf
[Service]
UnsetEnvironment=GBM_BACKEND
```

> 教训：**排查时不要往 systemd user manager 里塞临时环境变量**。它会跨进程、跨会话残留，
> 而且不会出现在任何 `git status` / 配置文件里 —— 这类「幽灵状态」能把后面的判断全部带偏
> （我一度以为根因是「NVIDIA 不支持」，其实是自己在 AMD 节点上按 NVIDIA 修饰符分配）。

### 3.4 代码层另外两个假设

- `PointerHandler` 只处理 Press / Motion / Release，`Axis`（滚轮）落进 `_ => {}` —— 协议里
  根本没有 scroll 语义，所以 presenter 把 wheel 合成为「手指向上拖」的手势（60 逻辑 px/tick，
  `DROIDLOOM_WHEEL=off` 可关）。原生 `ACTION_SCROLL` 要改 guest ABI（动镜像），这轮没做。
- `WindowDecorations::None` → 没有标题栏、可抓区域为 0，KWin 认为 `moveable=true` 但你抓不到。
  改成 `RequestServer` 装饰后拖动可用。

## 4. 验证（实测数据）

| 项 | 结果 |
|---|---|
| Arris 窗口 | 720×1200 合成进 KWin 窗口，标题栏 25px + 边框 4px，可拖可缩放 |
| 冷启动复现 | 16 s ready，窗口持续 126 s，`NRestarts=0`，无 `GBM failure` |
| 数据路径 | 零拷贝：`alloc` = `Android` = `KWin` 同一个 `0x0300000000606010`（KIND=6 / COMPRESSION=0） |
| 滚轮 | 内容滚动 ±424 px 对称、方向正确（host 侧注入，非 `adb input` 绕过） |
| 键盘 / 触摸 | Enter 推进页面、adb tap 推进到主页 |
| 其它 app | Settings ✅ / Files ❌（-91，未深究） |

截图见同目录：`arris-window-crop.png`（窗口特写）、`arris-onboarding.png`（首次引导）、
`arris-mainpage.png`（主页 + 通知）、`desktop-20261110.png`（整屏，窗口在右侧）。

## 5. 已知限制

- 滚轮是**兜底手势**，不是原生 scroll；`DROIDLOOM_WHEEL=off` 可关。
- 脚本化点击用 `adb shell input tap`；`ydotool` 的合成事件进不了应用（虚拟设备无 `EV_ABS`，
  绝对定位无效，还得靠一次性 KWin 脚本读 `workspace.cursorPos` 做闭环）。
- 窗口标题显示的是包名。
- `screencap` 在 cell 里只给空 PNG（`FLAG_SECURE`），像素级取证要靠宿主侧截图或 `uiautomator dump`。

## 6. 回滚

```bash
~/droidloom-nvidia/rollback.sh --dry-run   # 先看计划
~/droidloom-nvidia/rollback.sh            # 手输 rollback 确认，6 步回出厂
```

只退 presenter：`scripts/install-presenter.sh --rollback`（回 works-a2，滚轮/标题栏会一起消失）。
注意整套回滚 = **回到坏基线**（`NoRenderFormat` / `GBM failure` 会重现）。

## 7. 对项目的评价（修正我最早那句）

我最初判断它「在 NVIDIA 或多显卡机器上大概率卡在同一个坑」—— 这个判断**偏保守**。
真正卡人的不是架构，而是三条硬编码假设（只认 linear、wlroots 专有全局、没处理 axis/装饰）。
在 NVIDIA + KWin 上把这三条松开，补丁量很小，跑起来是零拷贝、延迟正常的。
现在它适合开发者自用；等这几条假设被上游改成「能力探测 + 降级」而不是「必需」，才谈得上日常可用。
