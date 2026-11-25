# MD380 Vocoder Notice

## Upstream project

Source: https://github.com/nostar/md380_vocoder

Copyright (C) 2020-2021 Doug McLain AD8DP
Based on code from https://github.com/travisgoodspeed/md380tools

The upstream source headers state: "either version 2 of the License, or (at
your option) any later version" of the GNU General Public License.  ARCH uses
the component under GPL-2.0-or-later.  A copy of GPL-2.0 is included in this
directory; the application as a whole is distributed under GPL-3.0.

## Firmware blobs

The Android build bundles the MD380 firmware/core images used by the bundled MD380 vocoder component:

- firmware.img
  SHA-256 c0351f250a834660641bca3b06be931c80cc1ed0ef808356c550b99cd0f4c632
- core.img
  SHA-256 d40398183e9aa8db6288a62d9a0e0c233cb143ef5633680abd829cc03c719f66

The files are copied into app-private storage after APK installation and are
never downloaded from any third-party server at runtime.

## ARCH bridge

app/src/main/jni/md380_jni.c is an ARCH-authored JNI bridge that calls the
upstream exported md380_decode_fec() and md380_encode_fec() entry points for
the 9-byte DMR/XLX voice payload used by the existing ARCH protocol stack.

## ARCH JNI namespace

The bundled prebuilt MD380 JNI exports were normalized to the ARCH namespace
`cn.arch.dmr.archzjegwmq.ArchVocoder`. The renaming preserves native machine
code, symbol length and GNU hash values; reproducibility tooling is provided
under `tools/`. The upstream vocoder implementation and firmware remain subject
to the notices above.