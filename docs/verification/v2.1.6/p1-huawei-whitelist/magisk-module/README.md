# 华为媒体卡白名单覆盖模块（Magisk）—— **未验证，不出货**

> ## ⚠️ 这个模块没有在真机上验证过，也**不随 v2.1.6 发布**
>
> 2026-09-25 决定：**暂停这条路线**。原因很直接 —— WGR-W09 没有 root
> （`su` exit=127、`adb root` 被拒），而华为机型 root 成本极高。
> 本目录保留模块生成器与模块本体，只是为了让「唯一诚实的那条路」有据可查，
> **不是**一个可以照刷的成品。任何人在刷之前都必须先读完本节。

## 它想解决什么

华为控制中心媒体卡的准入判据是 ROM 只读分区里的一份**包名白名单**
（`/system/emui/base/thirdappfilter/third_app_filter.xml` → `<feature name="mediaplaybackcontroller">`
→ `<function name="mediasession">`，51 条）。包名不在名单里就一律被
`MediaControlUtils.isInMediaSessionOrStyleList(...,2)` 拒绝，卡片显示「未在播放」。

完整取证：[`../whitelist-criterion.md`](../whitelist-criterion.md)。

应用侧没有任何自助入口（不是签名、不是清单声明、不是 session 条数）。
唯一**不冒用身份**的办法是：由设备所有者在自己机器上把包名加进那份名单。

## 为什么是「覆盖同一个文件」而不是走 COTA

`huawei.cust.HwCfgFilePolicy.getDownloadCfgFile` 的 smali 逻辑是
（原文：[`../23-decompile-HwCfgFilePolicy-cota.txt`](../23-decompile-HwCfgFilePolicy-cota.txt)）：

```
infos = getFileInfo("/data/cota/para/", verDir, filePath)          # COTA 先读
for (dir : getCfgPolicyDir(0))
    if (isPresetNewerVersionInfo(getFileInfo(dir, ...), infos))    # 谁版本新谁赢
        infos = 那个
```

也就是说 **COTA 并不天然优先，是「版本号新的赢」**。走 COTA 就必须连 `version.txt`
一起伪造一个更大的版本号，反而更容易撞上 `compatibleVersion` 之类的校验。
Magisk 的 magic mount 直接覆盖 `/system` 下那一个文件，不参与版本比较 —— **假设更少**。

## 生成与刷入

```bash
# 1. 从设备重新生成（可复现：就地插入一行，其余字节不变）
./build.sh <serial>
#    产物: ./module/  （module.prop + system/emui/base/thirdappfilter/third_app_filter.xml）

# 2. 打包
cd module && zip -r ../ncrust-huawei-mediacard.zip . && cd ..

# 3. 刷入（Magisk app → 模块 → 从本地安装），然后重启
```

## 刷完怎么验证（**这一步必须做，不要只看模块装上了**）

```bash
# a) 文件真的被覆盖了吗
adb shell md5sum /system/emui/base/thirdappfilter/third_app_filter.xml
adb shell grep -c takahashirinta /system/emui/base/thirdappfilter/third_app_filter.xml   # 期望 1

# b) ROM 还拒吗（清 logcat → 让应用 pause/play 重新触发判定）
adb logcat -c && sleep 1
adb shell input keyevent 127 && sleep 2 && adb shell input keyevent 126 && sleep 4
adb logcat -d | grep -i "media white list"
#    期望：**没有** com.takahashirinta.ncrust 这一行

# c) 卡片
#    用 ../regression/card-ab.sh <serial> after 读 uiautomator 的 music_item 文本节点
```

## 已知风险 / 未验证点（刷之前请逐条对照）

| # | 风险 | 说明 |
|---|---|---|
| R1 | **整条路未经真机验证** | 没有 root 设备可测。上面每一条都是「应该」，不是「实测」 |
| R2 | `getFileInfo` 是否还校验别的字段 | 只逆到 `isPresetNewerVersionInfo` 用版本比较；`getFileInfo` 内部（是否校验 `compatibleVersion` / 签名 / 哈希）**未逆完** |
| R3 | Magisk 在本机是否可用 | 华为 / HarmonyOS 对 Magisk 的支持视机型与版本而定，本机**未验证** |
| R4 | `/system` 是 erofs 只读 | Magisk magic mount 理论上做文件级覆盖；本机未验证 |
| R5 | 系统 OTA 后是否被还原 | Magisk 模块通常能存活，但华为的校验机制未验证 |
| R6 | 只加了 `mediasession` 一段 | `mediastyle`（8 条）/ `callapp`（4 条）没动。若要 MediaStyle 通知那套也生效，理论上要加进 `mediastyle` —— 但那条与本问题（卡片）无关，**没做** |
| R7 | `hw_mediaplaybackcontroller_app_config.xml` 没动 | 那个文件的**运行时读取点未定位**（见 `whitelist-criterion.md` §8.3 U1），所以没有一并覆盖 |

## 回滚

Magisk app 里停用或删除模块，重启即可 —— 模块不写入任何用户数据，
`/system` 上的原文件从未被真正修改（magic mount 是挂载层覆盖）。

## 与前一个方案的边界

本模块**不改包名、不冒用签名、不蹭别家会话标识**，只是设备所有者在自己机器上
把 `com.takahashirinta.ncrust` 加进一份系统配置。它与「把包名改成
`com.netease.cloudmusic`」有本质区别：后者会顶掉真正的 ncm（Android 不允许同包名共存），
属于身份冒用，且违反本项目铁律。
