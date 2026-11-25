# ARCH 公网版

ARCH 公网版是面向 PNC380、P50 等公网终端硬件环境的独立 Android 客户端。

## 当前版本

- 包名：`cn.arch.dmr.network`
- versionCode：`291`
- versionName：`1.3.30-NETWORK-PUBLIC-291-AUTOSTART-EXIT-95`
- OTA：`https://hljrsa.cn:50113/releases/ota/arch-network-version.json`

## 构建

```bash
gradle assembleRelease
```

正式构建需要 JDK 17、Android SDK 34 和 ARCH 发布签名密钥。仓库不包含 keystore、密码或 `local.properties`。

## 说明

`HYTERA_EDITION=true` 是此版本用于启用 PNC380/P50 等设备硬件兼容路径的内部构建开关，不代表仓库仍包含独立的“海能达版”发行版本。

## 许可

GNU GPL v3.0。第三方组件和专利提示见 `app/src/main/assets/licenses/`。