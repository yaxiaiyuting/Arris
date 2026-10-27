# Arris v3.7.10-gpl 验收记录

- 版本：`3.7.10-gpl` / `versionCode 96`
- 修复清单与已知未修：[`RELEASE_NOTES.md`](RELEASE_NOTES.md)

## 构建与测试

| 项 | 结果 |
|---|---|
| `:app:testDebugUnitTest` | **3314 通过 / 0 失败** |
| `:app:lintDebug` | **0 error / 60 warning**（17 条基线内） |
| aapt2 badging | `versionCode='96' versionName='3.7.10-gpl'` |
| apksigner（V2） | `e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82` |
| `zipalign -c -P 16 4` | 通过 |
| release dex 探针泄漏 | `ProtocolProbe` 条目 **0** |

## 本轮执行方式

5 个并行子代理按文件域分工（H13+E10 / H10+H21 / J10+H14 / K05 / J15），
集成阶段由主代理补：`maximize` 的 Int 平方溢出（子代理报告里发现的第三条）、
J10 的 `qq_songid` 落盘与读路径幂等迁移、队列/单曲状态的 QQ 迁移接线、`libraryTick` 死字段清理、
以及 J15 重定基线后的两处期望值。

## 未验证项

- 未做真机验证；H13 的新路由模板未跑仪器测试（navigation-common 2.8.5 的可选查询参数语义按字节码核对，并有源码扫描用例锁 `defaultValue`）。
- H14 的三张次级身份表（离线索引 / 歌词缓存 / 本地歌单）仍存旧 AV id，未迁移（见 RELEASE_NOTES）。
