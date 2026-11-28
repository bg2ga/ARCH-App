# 源码说明

## 对应正式发布包

### 统一工程：四套 flavor

- 文件：`ARCH-Android-source-3.2.172-3.2.156-3.3.120-1.0.4-SECURITY-20261128.zip`
- SHA-256：`b80e602c5bc20e3a80aac9fbc12021d48e79c8a2a0765143580242e113716e49`
- 包含：公开版 3.2.156、协会版 3.2.172、海能达版 3.3.120、海外版 1.0.4

### 独立公网版

- 文件：`ARCH-Network-source-1.3.30-NETWORK-PUBLIC-295-SECURITY-95.zip`
- SHA-256：`1f981699c1c304e150bc923ad235c1950b43cbf1e1ea4a36f4d99117ddfbc055`
- 版本：1.3.30-NETWORK-PUBLIC-295-SECURITY-95

## 目录修正说明

正式发布 ZIP 将 Android 模块的 `src/` 直接放在了压缩包根目录，但 `settings.gradle` 仍引用 `:app`。GitHub 版本已按标准 Android 工程结构恢复为 `app/src/`，并补回同次发布工作区中的 `app/build.gradle` 和 `app/proguard-rules.pro`，使仓库可以直接构建。

公网版发布 ZIP 中附带的 90 个历史 `.bak`/`.before` 文件未提交到仓库。

GitHub 分支还附加了 README、构建说明、许可证汇总、安全说明和 `.gitignore`，因此 GitHub 自动生成的分支归档哈希会与原始源码 ZIP 哈希不同。

## 不包含的内容

本仓库不包含 APK、AAB、正式签名证书、签名密码、API Token、用户配置或设备数据。