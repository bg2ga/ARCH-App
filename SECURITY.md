# Security Policy

## Reporting a vulnerability

Please do not disclose security vulnerabilities in a public issue. Contact the repository owner through the contact method listed on the GitHub profile.

## Repository hygiene

This repository must not contain:

- Android signing keystores or passwords
- API tokens, private keys, or WireGuard configurations
- User account exports, radio credentials, or device dumps
- Locally generated APK/AAB files

If a credential is committed, revoke or rotate it first, then remove it from Git history.