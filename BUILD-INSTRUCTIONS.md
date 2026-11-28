# 构建说明

## 环境

- JDK 17
- Android SDK Platform 34
- Android Build Tools 34.0.0
- 仓库自带 Gradle Wrapper 8.9
- Android Gradle Plugin 8.5.2

## 统一工程：四个 flavor

在仓库根目录执行：

```bash
./gradlew clean
./gradlew assemblePublicEditionRelease
./gradlew assembleAssociationRelease
./gradlew assembleHyteraInternalRelease
./gradlew assembleOverseasRelease
```

输出目录：

```text
app/build/outputs/apk/publicEdition/release/
app/build/outputs/apk/association/release/
app/build/outputs/apk/hyteraInternal/release/
app/build/outputs/apk/overseas/release/
```

## 独立公网版

```bash
cd network-edition
./gradlew clean assembleRelease
```

输出目录：

```text
network-edition/app/build/outputs/apk/release/
```

## 签名

仓库不提供正式签名密钥。发布正式安装包时应在本地通过环境变量或未提交的 Gradle 配置提供签名信息。

不得提交：

- `*.jks`、`*.keystore`
- `keystore.properties`
- `local.properties`
- APK、AAB、`.idsig`
- 密码、Token、私钥和用户数据