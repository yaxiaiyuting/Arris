# v2.4.0 证据索引

## 探针（先落盘，后写码）

| 文件 | 内容 |
|---|---|
| `PROBE-SUMMARY.md` | **总纲**：艺人/专辑/单曲匹配可行性与准确率、降级策略、版权字段、两源接口差异、由探针直接决定的设计取舍、未验证项 |
| `probe-artist-mapping.md` | 11 位艺人；两源 ID 体系不互通（无 ISRC/指纹）；朴素同名算法在邓紫棋上给 NONE 而正确是 EXACT |
| `probe-album-mapping.md` | 48 对专辑；名称需归一化才配上的 15 对；曲目数只一致 41/48；发行方一致仅 8/34（不可用） |
| `probe-song-mapping.md` | 230 首；`media_mid == mid` **0/230**；时长差分布（≤1s 164 / 1~2s 20 / 2~10s 24 / 10~30s 7 / >30s 15） |
| `probe-copyright-field.md` | 三页接口的版权字段存在性；金标准的坑（试听片段）；ncm A/B 混淆矩阵（假阳 0 / 假阴 0）；QQ 判据区分度表 |
| `probe-artist-api.md` / `probe-album-api.md` / `probe-song-api.md` | 两源三页的端点、**实测扫出来的**字段表、分页机制差异、聚合必须补的东西 |
| `probe-raw/` | 全部原始响应 JSON |
| `probe_lib.py` + 四份 `probe-*.py` + `probe-catalog-api.py` | 可复跑的探针脚本（**匿名只读**） |

## 测试与构建

| 文件 | 内容 |
|---|---|
| `verification/build-summary.txt` | 单测 935/0/0/0、lint 0 error 8 warning、两个 APK 的 versionCode/versionName 与 release sha256 |
| `verification/device-smoke.txt` | 真机（WGR-W09）安装 + 冷启动冒烟：零崩溃；**以及被阻塞的交互式验证** |
| `verification/README.md` | 证据目录说明 |

## 三源交叉校验（versionCode）

- `bash tools/next-version.sh`（**带 fetch**）→ 三源一致 = **39** ⇒ 下一个可用 **40**
- `aapt2 dump badging dist/Ncrust-v2.4.0-gpl-release.apk` → `versionCode='40' versionName='2.4.0-gpl'`
- `git show v2.4.0-gpl:app/build.gradle.kts` → `versionCode = 40` / `versionName = "2.4.0-gpl"`
- ⇒ **tag / APK / build.gradle 三源一致**

## 产物与发布

- `dist/Ncrust-v2.4.0-gpl-release.apk`（10,010,056 字节，sha256 前 16 = `2b9576aea55a5b87`）
- `dist/Ncrust-v2.4.0-gpl-debug.apk`（30,664,046 字节）
- draft release：`v2.4.0-gpl`（`gh release view` 实测 `isDraft: true`，两个 APK 均已上传）
- tag 与 `HEAD` **同一提交**（`5ab7393`）；`git diff v2.4.0-gpl HEAD -- app/` 为空
- ⚠️ 一次**未发布 tag 的重建**：第一次打 tag 后，模拟器验证抓到「回显虚高」bug 并修复，
  此时 release 仍是 **draft（从未发布）**，按 v2.1.3 的先例删除并重建了 tag，
  使 tag 落在「产出这批 APK 的那个提交」上。**已发布的 tag 一个都未移动。**

## 未验证缺口清单（如实，不伪造）

1. ~~交互式 UI 全链路验证未执行~~ → **已在 API 24 模拟器上补做并通过**（见 `verification/device-smoke.txt`
   的「追加」一节）：特性 A（艺人页双源聚合 + 三档切源 + 「仅 qm 有」标注）、
   特性 B（专辑页聚合 + `默认播放源：ncm` + `匹配：完全一致` 可追溯）、
   特性 C（`两源版本` 并列）均**实测通过**。
   **真机（WGR-W09）仍被锁屏阻塞**（需人脸识别/密码，本 session 无解锁凭证）——
   真机只完成了「安装 + 冷启动 + 零崩溃」这一层，交互链路是模拟器上的证据。
   另外模拟器验证**抓到并修复**了一处诚实性 bug（单曲页在无确证可播时仍称
   「已默认选中有版权的音源」），已补 2 条回归单测。
2. **探针全程匿名态**（真机未 root、`adb root` 被拒、release 不可 `run-as`）。
   登录态下 QQ `purl` 覆盖率与 ncm `pl` 取值会不同（方向是「更多可播放」），
   文档中的数字是匿名口径。
3. **QQ 专辑页的跨源匹配在锚点名称为空时退化为不匹配**（路由不携带专辑名，
   代码里已记录；有名称时正常匹配）。
4. **繁简/别名归一化未做**：`周杰伦` 与 `周杰倫` 视为两个艺人。
5. **艺人页不逐张专辑探测版权**（44 张 × 2 源 = 88 次请求），默认源由该艺人曲目列表的可播放比例决定。
6. **QQ 单曲详情是否有批量端点未穷举**（本轮只验证了单首形状）。
7. **匹配算法在极端同名场景的全量准确率未人工标注**（只验证了 top20 召回 + 专辑数排序能锚到真身）。
8. **搜索结果页长按菜单里的「单曲信息」点击未跳转**（专辑页的同一菜单项可以跳转），
   疑似该页底部菜单与播放器卡片的命中区重叠。**这是 UI 缺陷，不是验证失败** —
   本版单曲页在「专辑页曲目长按菜单」这条路径上可用，待下一版复核。
