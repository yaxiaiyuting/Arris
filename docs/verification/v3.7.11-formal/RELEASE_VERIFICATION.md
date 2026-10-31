# Arris 3.7.11-gpl 正式包验证

完成时间：2026-10-29 04:52（Asia/Shanghai）。本轮交付为本地正式整合包，无 GitHub 推送、标签或 Release。

## 源码与版本

- 分支：feat/bilingual-media-lyrics；构建源码提交 `f05a88a317168204f0af4013824c4832c331fb5a`。
- app 源码树：`88993fd7d4847c728706b59afd68906db9cd24b4`；文档收尾提交不改变 app 源码。
- 正式显示版本 **3.7.11-gpl**，内部 **versionCode 99**。重新运行带 fetch 的 next-version.sh，三源最大值98、取99，原始结果在 next-version.json。
- 整合原工作区31文件快照0fd05bf、双语媒体歌词774db28、歌名/歌手修正82401ec及本轮集中修复。原 Arris-playback-fixes 的31处他人变更保留；与已保存快照的 app 差异仅原版本配置。
- 原3.7.12/98测试包未覆盖，复核SHA-256仍为 `ce9ca0abe3ec8afcd7d5d97a443091b998b9c41ad6e8191d75a54241c4990077`。

## 检查结果

| 检查 | 结果 | 证据 |
|---|---|---|
| 完整 JVM | 3366项、308类；0失败/0错误/0跳过 | jvm-results.zip / verification.json |
| Lint Debug | 0错误、61警告、7信息 | lint-results-debug.xml / .txt |
| Debug / Release | 构建成功 | full-build.txt |
| APK版本 | 3.7.11-gpl / 99 | apk-badging.txt |
| Release签名 | 校验通过，沿用原发布证书 | apk-signature.txt |
| 产物指纹 | APK、Debug、mapping分别归档 | verification.json / 产物目录SHA256SUMS |

完整命令：

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew --offline --no-daemon '-Dorg.gradle.jvmargs=-Xmx6g -XX:MaxMetaspaceSize=1g -Dfile.encoding=UTF-8' :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --console=plain
```

首次完整运行仅文案守卫未收录英文spectrum词形而失败。初次日志、失败XML、统计单独保留；f05a88a补齐词表后完整重跑成功。未将失败轮与成功轮相加。定向回归、初次夹具错误与后续结果在 ../pending-3.7.11-formal/repair/；历史错误探针继续保留，不能把断言错误存在的旧探针计作修复回归。

发布证书SHA-256：`e62eca39b7e5e36bc9a05f83688dcfecc11fc5589a4097f7dbbaaa7ddfbbec82`。

## 产物

- `Arris-v3.7.11-gpl-formal-release.apk`：12,121,836 bytes，SHA-256 `987504af32f3f9f0b0decaebba1e3049e65ca57682ed604e40c3c57bd3be4968`。
- `Arris-v3.7.11-gpl-formal-debug.apk`：34,035,189 bytes，SHA-256 `8010b040e72d74ad2b87c5639ccce0edff16829bd25dc1dfc50a3fc32c3174a4`。
- `Arris-v3.7.11-gpl-formal-mapping.txt`：80,148,930 bytes，SHA-256 `6999d4396890cc7b44ffd85f54718471c41dd92f732cb87af648074c35b6a606`。

正式目录：`/home/duanjb666/deepseek/dist/v3.7.11-gpl-formal-code99`。birthday离线资源22个条目已进入APK。测试产生的旧曲线测量文件已移入当前验证目录，原历史证据恢复为HEAD版本。

## 审查收口与验证范围

固定核心范围343/343个文件在审查基线具备同SHA执行代码通读记录。主应用当时388个Kotlin文件中375个通读，13个非核心为局部/未读，不发布全项目覆盖百分比。集中修复涉及的变更与调用方另作定向复核，保留历史指纹，不伪称修复后又通读全部文件。修复覆盖N/O/P及当前核实仍存在的J/H/K/E/F残余，详见CORE_FIX_TRACKER.md；多项是同一问题残余，不另造总缺陷数字。

全部Arris审阅任务已删除；本轮不再安排续审或续办。工作仅本地提交，没有推送、上传GitHub、创建新标签。

**未做真机/模拟器、可见浏览器、媒体播放、真实账号或云端写入验证。** 网络夹具均在本地拦截。桌面卡片冷启动、系统通知/Live Updates显示及布局效果仍需设备验收，JVM不替代动态验收。

U01封面过大遮挡：按用户要求仅记录，未缩放布局。旧B站裸ID的离线曲目表/歌词缓存缺少AV/AU载荷，无法安全自动改号，保留旧数据，可能重新取词/缓存；本地歌单有明确bv载荷的记录已迁移并合并。更早QQ散列缺qq_songid无法反推。双语额外歌曲信息是否显示由Android/ROM决定。完整修复说明见RELEASE_NOTES.md。
