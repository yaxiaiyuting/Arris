# 发版清单（每次出包都走一遍）

> 这份清单是**硬流程**，不是建议。漏掉任何一步，用户看到的版本就会互相打架：
> 应用里显示 3.8.14、官网卡片还停在 3.8.11、README 里找不到这一版。

## 0. 版本号

- 主线 `3.8.x`：`versionCode` 连续 +1（当前 **134** → 下一版 135）
- LTS `3.7.x`：`versionCode` 连续 +1（当前 **107** → 下一版 108）
- 两条线各自独立，互不借用；**不允许跳号、不允许临时改到别的次版本号**（3.9.x 那次教训）

```bash
bash ~/deepseek/tools/next-version.sh --code --no-fetch   # 交叉校验下一号
```

## 1. 构建与产物

```bash
JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./gradlew :app:assembleRelease --offline \
  -Pkotlin.daemon.jvmargs=-Xmx4g -Dorg.gradle.jvmargs=-Xmx9g
mkdir -p ~/deepseek/arris/dist/vX.Y.Z
cp app/build/outputs/apk/release/app-release.apk ~/deepseek/arris/dist/vX.Y.Z/Arris-vX.Y.Z-gpl.apk
sha256sum ~/deepseek/arris/dist/vX.Y.Z/Arris-vX.Y.Z-gpl.apk
aapt2 dump badging ... | head -1      # 确认 versionCode / versionName
```

## 2. git

```bash
git tag -a vX.Y.Z-gpl -m "…" <产出 APK 的那个提交>
git push origin <branch> && git push origin vX.Y.Z-gpl
git show vX.Y.Z-gpl:app/build.gradle.kts | grep version   # tag 必须指对提交
```

## 3. GitHub Release

```bash
gh release create vX.Y.Z-gpl <apk> --latest --title "Arris vX.Y.Z-gpl" --notes-file notes.md
```

- LTS 不新建 release：**替换滚动 release `lts-3.7` 的附件**，并 `git tag -f -a lts-3.7`
- 草稿会累积，发完顺手清理上一版草稿

## 4. 官网（三件事，缺一不可）

```bash
cd relay
cp <apk> public/dl/ && cp public/dl/Arris-vX.Y.Z-gpl.apk public/dl/latest.apk
node tools/sync-dl-manifest.mjs     # 让 /api/releases 知道本站托管了哪些文件
node tools/snapshot-releases.mjs    # 静态快照兜底（GitHub 被限流时用）
# 首页文案：版本号、一句总结、release-grid 四张卡片（最新一版放最左）
node tools/sync-homepage.mjs
wrangler deploy
curl -s https://arris.de5.net/get/latest.apk | sha256sum   # 对齐产物
```

## 5. README

- 顶部徽章：version / APK 体积
- **版本历史**里补一行（日期 + 一句话）
- 若默认下载地址或最低系统变了，同步改「发布通道」章节

## 6. 收尾

```bash
git checkout -b tmp/merge origin/master && git merge --no-ff <branch> && git push origin HEAD:master
```

真机验证（至少一条）：

```bash
adb logcat -s UpdateService:* UpdateDownloader:* SongUrlFetcher:* MediaHttp:*
```
