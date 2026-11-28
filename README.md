# ARCH Android 对讲应用

ARCH 是面向业余无线电、DMR、网络对讲和融合通信的 Android 应用。本仓库公开 2026-11-28 安全与可靠性版本的可构建源码，包含统一工程中的四个 flavor，以及独立的公网版工程。

## 当前版本

| 版本 | versionCode | 应用 ID | 构建方式 |
|---|---:|---|---|
| ARCH 公开版 `3.2.156-PUBLIC` | `674` | `cn.arch.dmr.public` | `./gradlew assemblePublicEditionRelease` |
| ARCH 协会版 `3.2.172-ASSOCIATION` | `691` | `cn.arch.dmr` | `./gradlew assembleAssociationRelease` |
| ARCH 海能达版 `3.3.120-HYTERA-INTERNAL` | `821` | `cn.arch.dmr.hytera` | `./gradlew assembleHyteraInternalRelease` |
| ARCH 海外版 `1.0.4-OVERSEAS` | `5` | `cn.arch.dmr.overseas` | `./gradlew assembleOverseasRelease` |
| ARCH 公网版 `1.3.30-NETWORK-PUBLIC-295-SECURITY-95` | `295` | `cn.arch.dmr.network` | `cd network-edition` 后执行 `./gradlew assembleRelease` |

## 本次更新重点

- 在线升级器强制 HTTPS，并校验受信域名、重定向、清单、SHA-256、包名、版本和正式签名。
- NRL 注册删除明文 HTTP 回退，限制证书照片和服务器响应大小。
- PTT 广播收紧导出权限，并校验 Android 14+ 厂商广播发送 UID。
- 录音重启和失败路径增加同步释放与发射撤销，避免假的“发射中”状态。
- 媒体会话拒绝未受信任控制器。
- 默认禁止明文流量，只保留业务所需公开域名白名单。
- 禁用应用数据云备份和设备迁移。
- 公网版补齐账号请求、更新器和 PNC 启动器签名校验。

## 仓库结构

```text
app/              统一工程：公开版、协会版、海能达版、海外版
network-edition/  独立公网版工程
docs/             各版本发布说明
gradle/           统一工程 Gradle Wrapper
```

## 构建环境

- JDK 17
- Android SDK Platform 34
- Android Build Tools 34.0.0
- Gradle Wrapper 8.9
- Android Gradle Plugin 8.5.2

正式签名材料不包含在仓库中。详细步骤见 `BUILD-INSTRUCTIONS.md`。

## 许可与安全

GNU General Public License v3.0 only，详见 `LICENSE`。第三方组件和专利提示见 `NOTICE.md`、`THIRD_PARTY_LICENSES.md`。仓库安全要求见 `SECURITY.md`。