# 安全政策

## 报告漏洞

请不要在公开 Issue 中披露尚未修复的安全漏洞。请通过 GitHub 个人资料中列出的联系方式联系仓库所有者。

## 仓库卫生

本仓库不得包含：

- Android 签名密钥或密码
- API Token、私钥、VPN/WireGuard 配置
- 用户账号导出、对讲凭据或设备转储
- 本地生成的 APK/AAB/idsig
- `local.properties`、`keystore.properties` 或编辑器备份

如果发现凭据曾被提交，应先撤销或轮换凭据，再清理 Git 历史。