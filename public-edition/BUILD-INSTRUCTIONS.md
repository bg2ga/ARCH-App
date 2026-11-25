# ARCH 公开版构建说明

## 环境

- JDK 17
- Android SDK 34
- Android Build Tools 34.0.0
- Gradle 8.7 或兼容版本
- Android NDK（仅在重新编译 JNI 时需要）

## 构建

```bash
cd public-edition
gradle clean assemblePublicEditionRelease
```

未签名 APK 位于：

```text
app/build/outputs/apk/publicEdition/release/
```

正式发布必须使用 ARCH 发布密钥签名，并重新验证包名、versionCode、versionName 和 SHA-256。