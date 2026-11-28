# ARCH公网版 1.3.30-NETWORK-PUBLIC-295-SECURITY-95

## 本次修复

- 更新器强制 HTTPS，限制 95 发布域名和 OTA/APK 路径；逐跳拒绝跨域/跨协议重定向。
- 更新清单严格校验 versionCode、versionName、apkUrl、size、sha256，并限制清单/APK 大小。
- 下载使用固定临时文件名，取消下载会断开连接、删除临时文件且绝不进入安装。
- 安装前校验包名、versionCode、实际大小、SHA-256、单签名和正式证书指纹。
- 默认禁止明文流量；只允许网络收音机实际使用的 `lhttp.qtfm.cn` 明文音频流。
- 禁用应用数据云备份和设备迁移。
- Android 5.x 密码使用私有随机密钥 AES-GCM 加密，不再落回明文。
- PNC 启动器复制 `poc.apk` 时校验正式签名。
- 账号 POST 禁止自动重定向、限制 64 KiB 响应体并校验最终 URL。
- AudioRecord 重启前同步停止并释放旧录音对象；录音未进入 RECORDING 状态时直接失败。
- 修正设置页版本显示，版本标签与安装包版本一致。

## 验证

- Release 编译成功。
- lintRelease：0 errors，71 warnings。
- zipalign `-p 4` 通过。
- apksigner v1/v2/v3 通过。
- 正式证书 SHA-256：
  `f442665a4a9c89c36ba8027aaa85063e839932a2d5dd47e90d0d69c911d52a97`
- APK SHA-256：
  `ee595fea75fd8b2ff46139ae1ac0487326d82e950779c20265e4c312aef4a58a`

## 说明

厂商 PTT 广播在 Android 7-13 上仍需要兼容厂商私有 ROM；客户端无法可靠取得发送 UID。本轮没有为了安全改动 DMR、数字中继、网络对讲、SIP、APRS 的语音路由主路径。
