# ARCH 业余无线电 Android App

本仓库只维护两个仍在发布的 ARCH Android 版本：

| 目录 | 应用 | 包名 | 当前版本 |
|---|---|---|---|
| `public-edition/` | ARCH 公开版 | `cn.arch.dmr.public` | `3.2.142-PUBLIC`（versionCode 660） |
| `network-edition/` | ARCH 公网版 | `cn.arch.dmr.network` | `1.3.30-NETWORK-PUBLIC-291-AUTOSTART-EXIT-95`（versionCode 291） |

协会版、海能达版和海外版已从本仓库撤下，不再作为本项目支持的发行版本。

## 仓库结构

```text
public-edition/   公开版独立 Android/Gradle 工程
network-edition/  公网版独立 Android/Gradle 工程
docs/releases/    两个版本各自的发布说明
tools/            native JNI 维护工具
```

两个工程彼此独立，分别构建，不使用同一个 Gradle root project。

## 构建

环境要求：

- JDK 17
- Android SDK 34
- Android Build Tools 34.0.0
- Gradle 8.7 或兼容版本

公开版：

```bash
cd public-edition
gradle assemblePublicEditionRelease
```

公网版：

```bash
cd network-edition
gradle assembleRelease
```

正式 APK 必须使用 ARCH 发布密钥签名。keystore、密码、`local.properties`、APK/AAB 和构建缓存不得提交到仓库。

## 许可与安全

本仓库按 GNU GPL v3.0 发布。第三方组件、专利提示与分发要求见 `THIRD_PARTY_LICENSES.md`、`NOTICE.md` 和各工程内的许可证文件。

AMBE/AMBE+2 等语音编码算法可能涉及独立专利或商业许可；GPL 不替代这些权利。分发二进制前必须完成相应审查。