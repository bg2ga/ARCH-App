# ARCH Android 对讲应用

ARCH 是一个面向业余无线电和网络对讲的 Android 应用。本仓库包含一套多 flavor 源码，可分别构建当前发布的协会版、公开版和海能达版。

## 当前版本

| 版本 | Gradle flavor | 应用 ID | versionCode |
|---|---|---|---|
| ARCH 协会版 `3.2.161-ASSOCIATION` | `association` | `cn.arch.dmr` | `680` |
| ARCH 公开版 `3.2.144-PUBLIC` | `publicEdition` | `cn.arch.dmr.public` | `662` |
| ARCH 海能达版 `3.3.109-HYTERA-INTERNAL` | `hyteraInternal` | `cn.arch.dmr.hytera` | `810` |

本次发布源码日期：2026-11-28。

## 本版重点

- NRL 服务器目录使用双源自动更新与失败回退。
- 服务器目录、频道和服务器密码按服务器分别缓存。
- 心跳、离线判定和自动重连逻辑更新。
- NRL 软件设备身份与官方 Android 客户端对齐。
- 重做服务器选择器和频道空状态，改善小屏布局。
- 未修改 DMR、数字中继、网络对讲、D-Star、SIP、APRS 的主要业务逻辑。

## 构建

环境要求：

- JDK 17
- Android SDK 34
- Android Build Tools 34.0.0
- Gradle 8.7（兼容 Android Gradle Plugin 8.5.2）

```bash
gradle assembleAssociationRelease
gradle assemblePublicEditionRelease
gradle assembleHyteraInternalRelease
```

正式签名材料不包含在仓库中。分版本说明见 `docs/`。

## 仓库说明

本仓库只发布可公开的源码，不包含 APK、AAB、签名密钥、密码或本地构建配置。第三方组件和专利提示见 `THIRD_PARTY_LICENSES.md` 与 `NOTICE.md`。

## 许可

GNU General Public License v3.0 only，详见 `LICENSE`。