# 构建说明

## 环境

- JDK 17
- Android SDK Platform 34
- Android Build Tools 34.0.0
- Gradle 8.7
- Android Gradle Plugin 8.5.2（由根目录 `build.gradle` 指定）

## 构建三个当前版本

在仓库根目录执行：

```bash
gradle clean
gradle assembleAssociationRelease
gradle assemblePublicEditionRelease
gradle assembleHyteraInternalRelease
```

输出目录：

```text
app/build/outputs/apk/association/release/
app/build/outputs/apk/publicEdition/release/
app/build/outputs/apk/hyteraInternal/release/
```

## 签名

仓库不提供正式签名密钥。如需生成可安装包，请在本地通过环境变量或未提交的 Gradle 配置提供签名信息。

不得提交：

- `*.jks`、`*.keystore`
- `keystore.properties`
- `local.properties`
- APK、AAB
- 密码、Token、私钥和用户数据

## 验证

本批安装包由发布方在 2026-11-28 完成 Release 编译和使用 ARCH 正式证书签名。GitHub 仓库仅发布对应的源码。