# Arris v3.5.0-gpl

**versionCode 75** ｜ 基于 `sync/ncrust-142e908`（app 源码 `0c455ac`）的整合发布

**包名与签名不变，可直接覆盖安装 v3.4.12；本版没有引入破坏性的持久化迁移。**

> 这是一次**整合发布**：把已经合入当前分支的上游 142e908 功能按逻辑整理成一个版本，
> **不再加入新功能，也不做重构**。发布范围就是当前已整合的功能。

---

## 一、收藏写入与扫码登录

- **xeapi 收藏写入 + `checkToken`**：收藏写接口迁到 xeapi，补齐 `checkToken` 与设备身份/
  公钥状态的持久化；保留 Arris 原有的「只加不减」收藏合并和多音源写闸门，
  xeapi 失败时仍回退到旧通道。
- **PC eapi 扫码登录**：申请与轮询统一 `type=3`，登录成功后先用 `account/get`
  校验新会话，再保存 803 会话；保留 Arris 的身份 Cookie 扩展与手机→平板局域网交接。
  两条扫码反馈文案已并入八语文案。

## 二、队列身份与播放上报

- **队列按 `TrackKey` 回查**：不再用裸 `song.id` 决定「当前队列项」。跨源同 id
  （ncm 123 / QQ 123）是两首歌；删除重定位、乱序轮末不回绕、AUTO / 非 AUTO 过渡、
  ENDED / IDLE 恢复都按实际起播身份处理；预载槽位继续遵守 v1.5.2 的串台不变量。
- **clientlog 上报**：播放上报改走 clientlog eapi（osx 身份），覆盖 startplay、
  80% 与自然结束，按 songId 去重；QQ / B 站合成 id 仍由唯一出口 `ReportGate`
  拦截，不冒充网易播放记录。
- **手动起播 / 自动接续 / 车机与媒体键**三条起播路径都补了上报时点，避免只报一部分。

## 三、QQ 续期：解析、loginType 与迟到响应

本版针对三条已定位的问题做了修复：

1. **snake_case / camelCase 双认**：服务端改下 `refresh_token` / `access_token` /
   `expired_at` / `refresh_key` 后不再整段解析为空，token 轮换不再被丢掉；
2. **`loginType=0` 不再被当成缺失**：缺失时按「上一次值 → musickey 前缀 → 0」
   回退，避免微信/QQ 参数分支取错；成功后 `mergeWith` 保留服务端未下发的字段，
   `expired_at` 优先于从 cookie 反推；
3. **迟到响应闸门**：刷新发起时记录 auth revision 与盘上凭证，成功落盘、失败冷却、
   `AUTH_EXPIRED` 终态都必须回到 `QqAuthStore.withAuthLock` 内重新确认登录态未变、
   凭证未被替换、uin 仍匹配，否则返回 `SKIPPED_STALE` 且不写任何状态。
   刷新在途时重登 / 换号 / 登出，旧响应不会再把新登录态打成过期。

另外，`QqApi` 每次取链前会 `refreshIfNeeded`，不再只在冷启动时碰一次运气；
读路径 `isUsableFor` 不再删除任何数据，避免旧 snapshot 清掉新账号刚保存的凭证。
取消异常在调用层显式重抛。

> **边界**：本版**没有**用真实 QQ 账号做「一周续期 / 常驻跨期」验证，
> 因此**不宣称「已复现并根治一周掉线」**。上面的提交闸门与解析修复有 JVM 单测覆盖，
> 真实服务端是否轮换 snake_case、`expired_at` 是否非 0，仍需真机日志确认。

## 四、账号权益与试听

- **单曲权益判定**：根据 `url` / `level` / `fee` / `freeTrialInfo` 与歌曲 privilege
  分出 Full / Trial / Unavailable；档位比较复用 8 档音质表，`sky` 等阶梯外档位
  不参与比较，避免误报「已封顶」。
- **试听可见**：展开态音质标签与折叠态 mini bar 都会显示「试听」，避免用户疑惑
  「为什么播到一半停了」。试听片段不参与 startplay / 80% / 自然结束上报。
- **会话与 VIP**：启动、回前台 ≥30 分钟、301 三路判定会话；失效只清请求层 cookie，
  保留「曾登录」标记与本地收藏，不清库。账号页显示 VIP 四态
  （有效 / 已过期 / 未开通 / 未知），失败一律 Unknown，不显示成「未开通」。
- 首页/库页失效横幅与账号页登录/退出通知已接线；QQ / B 站不走 ncm 权益判定。

> **边界**：没有真实会员 / 试听账号的完整验证，VIP 四态、301、试听时长与
> 「试听后自动跳过」仍需真机确认。

## 五、收藏全量播放、插播与追加

- **收藏整列播放**：`LikedPlayAll` 先放本地 QQ / B 站等非 ncm 条目，再按云端红心
  顺序，最后补本地手动收藏的 ncm 条目；tombstone 不复活，去重键是 `TrackKey`。
- **全量拉取**：`LibraryManager` 按 500 首/批、批间 250ms 拉取全部红心，
  单批失败只丢该批，不再因为一页失败放弃整列。
- **插播 / 追加**：收藏页单曲 tab 增加「下一首播放 / 追加到队列」菜单；
  保持「缓存先播后补」，后台补齐只在队列引用未变时追加，避免灌进别的播放上下文；
  部分详情缺失时如实提示，不假装完整。

> **边界**：没有用真实账号的完整收藏数据跑过全量；收藏写入的**隔夜保留**
> 也未验证（见第七节）。

## 六、小屏、性能与 offload

- **矮屏播放器**：新增 `HeightClass`（Regular / Compact / Tiny）与 `CoverGeometry`。
  矮屏收缩封面并水平居中，Regular 保持原满宽锚点；测量值只在 layout/draw 阶段读取，
  不把测量变化提升为整卡重组。Tiny 下控制栏压成一行，顶栏挂 3 个面板切换，
  歌词字号按档位缩放；大屏/自动旋转入口在 Tiny 下不挂载（与上游取舍一致）。
- **减少无谓收集**：歌词 / 进度 / 位置流改为 `collectAsStateWithLifecycle`；
  播放器收起时冻结喂给歌词与进度条的流，展开立即追上。
- **暂停 ticker**：进度 ticker 只在 `isPlaying` 时运行；暂停瞬间补发最终位置，
  暂停态 seek 由 `onPositionDiscontinuity` 覆盖，不再空转 2Hz。
- **offload 运行中门控**：用 `snapshotFlow` 监听可视化与动效的 PCM 需求，
  任一路需要音频数据就禁用 offload，全关后恢复 offload + gapless；
  没有 Handler/轮询，`onDestroy` 显式取消监听。
- **无缝播放默认值**：`gapless_playback` **缺键时默认 `false`**（设置页、ViewModel、
  默认值测试三处一致）；用户**已经显式写过 `true` 或 `false` 的保持原值**，
  不迁移、不降版本。设置行追加「测试中，可能不稳定」提示。

> **边界**：没有真机长期验证 offload 的实际音频路径切换；个别 ROM 切换时可能
> 有极短断点。小屏结论来自 API 33 模拟器 320dp 截图，不是真机。

## 七、应用内更新：源改为本 Fork 自己的 Arris 仓库

- 固定源 `yaxiaiyuting/Arris`，不加镜像；tag 只认 `vX-gpl`，资产只认
  `Ncrust-v<version>-gpl-release.apk` / `...-debug.apk`。
- 按当前 build type 选 release/debug 资产；不存在的通道宁可提示「无可用资产」，
  也不提示一个必然签名失败的包。
- 下载后依次校验：包名 → versionCode 严格更高 → 签名证书摘要；签名不一致只提示
  「需卸载当前版本 / 去浏览器下载」，不调起安装器。
- 断点续传在收字节前写 marker（URL / 资产身份 / 总长 / ETag），完整落盘后删除；
  同一 target 重复点击忽略，不同 target 取消旧任务并递增代次，旧进度不会覆盖新任务。
- 只读匿名请求 GitHub，不带 ncm/qm cookie；**不后台自动下载、不自动安装、不弹窗**。
- 关于页「检查更新」与用户页横幅/圆点是入口，`update_auto_check`（默认开）在
  关于组注册。

> **边界**：本版创建的是 **GitHub 草稿 Release**，没有正式发布，也没有执行
> 真实的线上更新下载/安装端到端验证；更新器对资产的识别将在用户发布后自然生效。

## 八、验收与证据

- 构建、全量 JVM 单测、`lintDebug`、`assembleDebug`、`assembleRelease` 的结果，
  以及两个 APK 的包名 / versionName / versionCode、签名证书 SHA-256、产物 SHA-256、
  mapping 归档，见 `docs/verification/v3.5.0/RELEASE_VERIFICATION.md`。
- 源码基线：`sync/ncrust-142e908`，app 源码 `0c455ac`；本版只改版本号与文档。

## 九、未验证的部分（如实，不夸大）

1. **真实 QQ 一周续期 / 常驻跨期**：没有真实账号的长周期验证，不把「一周掉线已根治」
   当作结论；需要真机日志确认服务端字段轮换与 `loginType=0` 行为。
2. **收藏隔夜保留**：xeapi 收藏写入的隔夜效果未验证。
3. **云端入账**：clientlog 上报的字段/顺序由 JVM 测试锁定，但没有真实回执，
   不能断言「云端已入账」；匿名播放也没有做账号云写。
4. **真机 offload**：模拟器不支持 offload，运行中切换与长期稳定性未在真机验证。
5. **真实会员/试听**：VIP 四态、301、试听片段与自动跳过的真机行为未验证。
6. **真实更新下载/安装**：只验证了代码路径与夹具，没有下载/安装真实 release 资产。
7. **真机 UI / 触摸**：矮屏布局来自 API 33 模拟器截图；真机触摸、音频输出、
   车机/Android Auto 未覆盖。

**请以真机测试为准。** 这一版是给你做真机验证的草稿发布，不会自动对外发布；
测试通过后再由你手动 publish。反馈时请附 `logcat`（QQ 续期、上报、offload 相关标签）
与出现问题的页面/操作步骤。
