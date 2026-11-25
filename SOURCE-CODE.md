# Corresponding source

This repository contains the corresponding source for the two supported ARCH Android binaries.

## Public edition

- Directory: `public-edition/`
- Package: `cn.arch.dmr.public`
- Source includes the Android application, Gradle build scripts, JNI bridge, DroidStar-derived MBE/IMBE source, blip25/Opus integration, licenses, attribution and build instructions.

## Network edition

- Directory: `network-edition/`
- Package: `cn.arch.dmr.network`
- Source includes the Android application, Gradle build scripts, native JNI source and prebuilt libraries, licenses, attribution, regression checks and build instructions.

## Release practice

For each distributed APK, publish the matching source revision at no additional charge. Keep the source commit and release version recorded in the release notes.

The retired association, Hytera and overseas edition configurations are not part of this repository.

## Native MD380 libraries

The prebuilt MD380 JNI libraries are imported from the ARCH digital-audio integration. Their JNI namespace was normalized to ARCH without changing the native implementation. Both ABI copies are checked for matching symbol length and GNU hash before acceptance. See `tools/README.md`.