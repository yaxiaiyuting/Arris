# ADR：第三方代码许可证策略（GPLv3 + AGPLv3）

- 日期：2026-10-29
- 状态：**需求方已确认**（"agpl 对我的软件来说没什么影响，我同意"）
- 适用范围：一切要从开源项目搬进 `Arris-playback-fixes` 的代码

## 1. 结论

1. **GPLv3 与 AGPLv3 可以合并**，依据是**双向**的 §13 条款，不是"兼容/不兼容"那种笼统判断：
   - **GPLv3 §13**：允许把 GPLv3 覆盖的作品与 AGPLv3 作品链接或合并成一个组合作品并分发；
     GPL 部分仍按 GPLv3，**整个组合还要满足 AGPLv3 §13 的网络交互条款**。
   - **AGPLv3 §13**：对称的另一半。
2. 因此本项目**接受**引入 AGPLv3 代码，代价是**对外分发的整体按 AGPLv3 口径**：
   若用户通过网络与之交互，需要提供对应源码。本地播放器的实际影响很小，但**许可证标签要改**。
3. **仍然排除 GPLv2-only**（典型：`jellyfin/jellyfin`）——GPLv2 与 GPLv3 互不兼容，无法合并。
4. **无 LICENSE 的仓库一律不搬**（法律上等同保留所有权利，最严格，不是"宽松默认"）。

## 2. 逐项目裁定表

| 项目 | 许可证 | 裁定 | 说明 |
|---|---|---|---|
| `bihua-university/alisten` | GPL-3.0 | ✅ 可搬 | 一起听后端改造首选（Go，支持网易云/QQ/B站） |
| `jofr/listentogether-app` | GPL-3.0 | ✅ 可搬 | 轻量双人一起听参考 |
| `jellyfin-mpv-shim` | GPL-3.0 | ✅ 可搬 | 客户端同步/重连参考 |
| `cojam` | MIT | ✅ 可搬 | 只需保留版权与许可声明；借 `clockSync` 算法 |
| `ShareAux` / `music-party` / `folium-party` | **AGPL-3.0** | ✅ **可搬（本次放开）** | 合并后整体按 AGPLv3 分发；协议端点表可参考 |
| `SongRec` | GPL-3.0+ | ✅ 可搬 | Shazam 指纹算法（`algorithm.rs`） |
| `Audile` | GPL-3.0 | ✅ 可搬（注意 minSdk） | 录音/Provider/错误分类参考；原项目 Android 8+，本项目已提到 29 |
| `shazamio` / `shazamio-core` | MIT | ✅ 可搬 | 协议细节参考 |
| `chromaprint` | LGPL-2.1 | ⚠️ 动态链接/独立进程才安全 | 不作为首选指纹方案（见识曲报告 §4） |
| `jellyfin/jellyfin` | **GPL-2.0-only** | ❌ **不可搬** | 与 GPLv3 不兼容 |
| `splayer-netease-together` | **无 LICENSE** | ❌ 不可搬 | 保留所有权利 |
| Apple **ShazamKit for Android** | 闭源 AAR | ⛔ v1 不用 | blocker 是**签名私钥不能进开源仓库**；许可另需律师确认 |

## 3. 落地要求

- 每个搬运的文件**必须**在文件头写清：原作者、原项目 URL、原许可证、改动摘要（与现有 `Ncrust —— ncm 第三方客户端` 文件头的写法一致）。
- 仓库根目录的 `LICENSE` / `NOTICE` 需要随采纳情况更新；若最终包含 AGPLv3 代码，**应用内「关于」页也必须显示 AGPLv3**（不能只写 GPLv3）。
- 只搬**算法与逻辑**、不搬对方 UI 资源时，仍要保留出处与许可证声明。
- 任何"从逆向协议里学到的接口"（Shazam / 网易云一起听）不算代码搬运，但要在报告与代码注释里标明**依赖第三方非公开接口**这一事实。

## 4. 未决

- 是否把整个仓库从 GPLv3 切换到 AGPLv3（一旦真的合入 AGPL 代码就必须切，届时更新 `LICENSE`、README、关于页、以及所有文件头）。
- 一旦切换，历史发布（3.7.x LTS / 3.8.0 之前）**不受影响**——已分发的 GPLv3 版本仍是 GPLv3。
