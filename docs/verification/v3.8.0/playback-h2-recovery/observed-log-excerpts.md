# 2026-10-30 已有手机日志观察

仅读取PLC110已有logcat，没有启动播放或识曲、没有更改设备状态。首次读取的工具输出观察到下列原文（从本聊天工具输出抽录，未留存首次全量原始文件，不据此推算请求总数）：

```text
10-30 01:10:01.106 20820 21939 W SongUrlFetcher: transport retry exhausted songId=553544026 level=jymaster
10-30 01:10:01.106 20820 21939 W SongUrlFetcher: h6.D: stream was reset: CANCEL
10-30 01:10:01.106 20820 21939 W SongUrlFetcher: Suppressed: h6.D: stream was reset: REFUSED_STREAM
10-30 01:10:07.593 20820 21938 I NcrustHttpTiming: path=/eapi/song/enhance/player/url/v1 ttfb=-1ms body=-1ms total=624ms failed=D
10-30 01:10:07.594 20820 20867 W SongUrlFetcher: transport retry exhausted songId=25841325 level=jymaster
```

之后再次读取时较早缓冲记录已不在本次输出中，只保留下述8秒超时条目，原始筛选行另存existing-device-log-redacted.txt：

```text
10-30 01:12:12.548 20820 22929 I NcrustHttpTiming: path=/eapi/song/enhance/player/url/v1 ttfb=-1ms body=-1ms total=8001ms failed=InterruptedIOException
```

结论：观察到正常播放取链的传输失败；不是把识曲解析错误当成已证实的播放根因。服务端限流、链路中间设备与连接复用分别承担多少原因仍未知；单凭这些日志不能确定。HTTP/1.1隔离恢复是针对已观察故障的客户端容错，需要后续设备验收确认真实网络效果。
