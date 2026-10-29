# HTTP/2 历史结论复核（2026-10-30）

用户要求轻量复核此前抓取为何未杜绝复发。本轮只读源码、历史报告与官方资料，没有运行新网络/设备探针，没有改业务代码。

## 历史对照及其边界

v3.7.6/RELEASE_VERIFICATION.md记录：同设备同接口，h2真实参数343ms/1067B，h1对照336ms/1067B；真实fetch连取10首共1895ms。其结论支持“当时h2可正常工作”，不支持永不拒流。该轮为行为探针，不是协议帧抓包。

历史报告归因请求风暴（53秒2064条）与陈旧连接失效；PING15s、空闲池30s后的冷启动97ms、闲置50s后92/96ms是当时场景验证，不能直接推定这次必为同一根因。

当前RetrofitClient仍有pingInterval(15s)、ConnectionPool(5,30s)，SongUrlFetcher仍传播取消并在传输错误后有界重试和冷却。没有观察到这几项修复被合并删除。

## 为什么仍可失败

1. PING是连接级探测，连接能回PONG并不等于每个业务流都会被接受。REFUSED_STREAM表示处理前拒绝流；CANCEL只表示流不再需要，单看错误字符串无法区分服务端取消、客户端主动取消或超时触发。
2. 30秒为**空闲**时间，不是连接最长存活时间；有活动请求时连接不因创建满30秒就被回收。evictAll同样只关闭空闲连接。
3. OkHttp4.12按周期发PING，在下一次周期检查未回复PONG。15秒PING并不能保证8秒业务截止前发现失效；根据故障相位，周期探测发现可跨过一个以上周期。这是机制上的可能窗口，不是本次已测时序。
4. 新网络/CDN出口策略、业务拒流、取消与并发仍可能导致类似表象。本次日志不足以证明历史请求风暴再次发生，也不足以认定只是一条坏连接从未被替换。

## 对已有ROOT-CAUSE-ANALYSIS.md的证据纠正

- ttfb=-1仅代表自定义计时器没有同时得到requestHeadersEnd和responseHeadersStart，不能断言请求头已经发出，不能排除DNS/TLS/排队阶段。该计时器并非原始报文抓取。
- OkHttp4.12 RealConnection.trackFailure：首次REFUSED_STREAM可能复用，但第二次会标记noNewExchanges；非本地取消的其他StreamReset同样会要求新连接。因此“OkHttp始终在同一坏连接上重试”不成立。
- maxRequestsPerHost=12是异步并发上限，不等于实际同时12个请求，也不证明同一时间只有一条连接。
- 同一时间有重置和超时，不足以证明它们发生在同一连接、由同一方造成。需要connection身份、连接/握手/发头阶段及取消原因记录。
- 7b27786的独立HTTP/1.1有界回退是容错路径；在真实网络验证前，不应将其当作已证明的服务端/连接根因或已证明恢复成功。

## 后续最小验证方向（本轮未执行）

同设备、同曲、同会话、低并发对照“全新h2 / 复用h2 / 全新h1”，记录每次连接身份、协议、复用/空闲时长、DNS/TCP/TLS/发头各阶段和主动取消/截止超时原因。只降协议或只缩PING都会混入变量，不能单凭成功就定因。

## 一手依据

- 历史实测：../../v3.7.6/RELEASE_VERIFICATION.md（真实路径为docs/verification/v3.7.6/RELEASE_VERIFICATION.md）。
- RFC9113 §7：https://www.rfc-editor.org/rfc/rfc9113.html#section-7
- OkHttp4.12 RealConnection.trackFailure：https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RealConnection.kt#L649
- RealConnectionPool.evictAll/cleanup：https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/connection/RealConnectionPool.kt#L110
- Http2Connection PING周期：https://github.com/square/okhttp/blob/parent-4.12.0/okhttp/src/main/kotlin/okhttp3/internal/http2/Http2Connection.kt#L136
