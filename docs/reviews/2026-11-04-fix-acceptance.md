# Arris 4.0.3 清单验收 · 2026-11-04

状态：**进行中**。首次验收提交 `d4ada44`，验收依据为 [I01–I18 清单](2026-11-04-confirmed-bugs.md)。5 小时续办 `arris` 已启用，完成本清单的判定与证据补测后停用；已知缺陷由用户安排。工作树中既有的文档、官网首页和下载资产清单改动不纳入这次业务验收。没有业务修改、构建、部署或真机操作。

## 先验收的 5 个 P1

| 条目 | 结论 | 证据与剩余边界 |
|---|---|---|
| I01 私人 FM | **通过原问题的源码验收** | [MainActivity.kt:2504](../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt#L2504) 用 `enterFm` 区分进电台与普通换队列，[2539 附近](../../app/src/main/java/com/takahashirinta/ncrust/MainActivity.kt#L2539) 的入口不再提前改模式，非空且仍属当前请求才切队列。普通换队列恢复非无限模式。未执行 Android UI。 |
| I03 分段下载取消 | **部分** | [UpdateService.kt:318](../../app/src/main/java/com/takahashirinta/ncrust/update/UpdateService.kt#L318) 在阻塞探测返回后才抓取当前代次。探测中取消/重试时，旧任务能拿到新代次，使用同名临时文件。传入的取消闭包在 [330 行](../../app/src/main/java/com/takahashirinta/ncrust/update/UpdateService.kt#L330) 仍比较同一全局值的两次读取，无法识别已有的代次变化。已登记 Call 可以被取消，已进入分段的旧任务增加了收尾校验，但旧任务 catch 中的全局 `cancelAll()` 可误取消新批次，交付检查与改名之间也未串行。下一轮补隔离并发探针。 |
| I11 房主身份 | **部分** | [Worker:563](../../relay/src/index.mjs#L563) 已拦截普通成员的 `snapshot`，本地同一入口确认普通成员仍能伪造 `closed`，触发其他成员的房主离开界面。房主仍由首个自报 `role=host` 的连接占用，没有房主凭据；已有房主时重复连接会被降级。证据：[Worker 探针](probes/2026-11-04-fix-acceptance/room-boundaries.mjs) · [日志](probes/2026-11-04-fix-acceptance/room-boundaries.log)。 |
| I14 P2P 报文 | **未通过，有新回归** | [P2pLink.kt:72](../../app/src/main/java/com/takahashirinta/ncrust/listen/P2pLink.kt#L72) 握手前就填好 `peer`，所以预设端口可在 `HELLO-ACK` 前发 DATA。错误来源分支在 [107 行](../../app/src/main/java/com/takahashirinta/ncrust/listen/P2pLink.kt#L107) 用 `return@thread` 结束整个接收线程。两个本地 UDP 端口复现了预设端口握手前 DATA 被接收，以及另一个端口发一包后合法 DATA 也收不到。应独立表示配置地址和已验证会话，逐包丢弃异常输入，并对会话数据做认证。 |
| I15 识曲取消 | **未通过** | [Provider:118](../../app/src/main/java/com/takahashirinta/ncrust/recognize/NeteaseRecognizeProvider.kt#L118) 用普通 `Job.invokeOnCompletion` 注册回调；同步 HTTP 尚在运行时，协程没完成，取消处理没有及时触发。隔离拦截器中取消后等待 150 ms，Call 仍未取消，协程要等响应放行才退出。应使用已有的可取消 HTTP 桥，取消时同步 `Call.cancel()`，并覆盖响应体读取。 |

I14/I15 的可复跑生产代码探针及输出见 [Kotlin 探针](probes/2026-11-04-fix-acceptance/kotlin-boundaries.sh) 和 [日志](probes/2026-11-04-fix-acceptance/kotlin-boundaries.log)。公网中继用进程内连接/目录替身，HTTP 全部用拦截器，UDP 只用环回地址。

## 其余条目初查

- **明确延期，非遗漏**：I02、I09、I13、I17、I18。与提交说明和用户确认一致。探针也确认 I09 的 `[1]` 仍报「已最新」，仅供下一轮修复参考。
- **历史撤回**：I06，见 `6ee90ed`。
- **I04 部分，待补探针**：[SegmentedDownload:179](../../app/src/main/java/com/takahashirinta/ncrust/update/SegmentedDownload.kt#L179) 核对起止和总长，双方都有 ETag 才对比。探测缺少 ETag 或任一分段不带 ETag 时，仍可能把无法确认同一实体的区段拼起来。写入前也未限定剩余分段字节数，过长正文可以先写入相邻区段再报错。
- **I05 部分，待补探针**：APK 走 Worker 切片回退时后缀算法与滚动别名缓存已改。[Worker:80](../../relay/src/index.mjs#L80) 仍只在没有 Range 时处理 `If-None-Match`，且弱 ETag 可以当作强 `If-Range` 匹配。独立的 [试听 handler](../../relay/src/studio-audio.mjs#L5) 本提交未改，原先两个条件请求问题仍在。
- **I07 通过原切网问题的源码验收**：切网路径已显式清理 [公共池](../../app/src/main/java/com/takahashirinta/ncrust/ui/viewmodel/PlayerViewModel.kt#L746)，原 Retrofit、Media 和 B 站池仍各有调用。AppHttp 的登记实现只加入默认池，派生客户端之后覆盖的池不会自动加入；未做真实切网复用验证。
- **I08 部分**：[SongUrlFetcher.kt:202](../../app/src/main/java/com/takahashirinta/ncrust/player/SongUrlFetcher.kt#L202) 改为按登录代次判定，使用单调时间；但首次拒绝就把有未来到期时间的条目放入 map，`levelDenied` 不看不同歌曲计数。第二首会直接跳过该档，达不到注释要求的两首；下一轮补生产逻辑探针。
- **I10 原混挂样例通过，另有边界待核对**：普通 Release 混挂主线与 LTS APK 已能选到合规 LTS；`lts-*` 标签分支只看当前客户端是否为 LTS，候选资产版本线的守卫仍需补测。
- **I12 部分**：房主离开时已请求关闭成员 socket，常规退出路径在替身中通过；[Worker:525](../../relay/src/index.mjs#L525) 无房主仍接收新成员，通知后不关连接，随后 `beat()` 也不要求房主存在。下一轮补无房主进房和关闭交错的 Worker 探针。
- **I16 空 503 回归通过**：生产 Provider 已返回服务器错误，补验空 401/403 和 2xx 空体控制用例。非 2xx 的正文仍先读后分流，不能据此声称整个响应读取边界已解决。

## 下一轮断点

先完成 I03 并发、I04 缺实体、I05 条件请求、I08 一首即屏蔽、I10 LTS 标签、I12 空房和 I16 状态用例。使用提交快照区分验收版本和并行发布变更。若业务提交更新，先验证遗留项是否已经变化。开始时五小时/周剩余 14%/87%，P1 与首轮探针完成时 11%/86%，本轮在阈值附近留档。续审只补清单证据和判定。
