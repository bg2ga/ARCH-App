# Third-party licenses and attribution

## DroidStar

Source: https://github.com/nostar/DroidStar
License: GNU General Public License v3.0

The files under app/src/main/jni/dstar/mbe and app/src/main/jni/dstar/imbe_vocoder are derived from the DroidStar project and its upstream IMBE/mbelib components. Original copyright notices and license headers are retained in those files. ARCH modifications are limited to the Android JNI bridge and mobile client integration.

## mbelib

Source: https://github.com/szechyjs/mbelib
License: ISC-style license, with the upstream patent notice retained.

## IMBE vocoder by Pavel Yazev

Copyright (C) 2009 Pavel Yazev.
The source is used as part of the DroidStar/DVM/OP25 ecosystem. The patent notice and source headers apply.

## blip25-vocoder

Source: https://github.com/OpenBLIP25/blip25-vocoder
License: MIT.
Used for the existing DMR/P25/NXDN vocoder path.

## Patent notice

AMBE/AMBE+2 and related voice coding algorithms may be covered by patents or require a separate patent license in some countries. The GPL and other source-code licenses do not by themselves grant patent rights from third parties. Public distribution requires separate review of applicable patent and commercial licensing obligations.

## MD380 vocoder and firmware

Source: https://github.com/nostar/md380_vocoder

Copyright (C) 2020-2021 Doug McLain AD8DP.
Based on code from https://github.com/travisgoodspeed/md380tools.
License: GNU General Public License v2.0 or later. The APK bundles
`libmd380vocoder.so`, `libmd380emu32.so`, `firmware.img` and `core.img`.
The firmware/core SHA-256 values are recorded in
`app/src/main/assets/licenses/MD380-NOTICE.md`.
The ARCH bridge `app/src/main/jni/md380_jni.c` is GPL-3.0-compatible and
provides the 9-byte DMR/XLX FEC interface used by ARCH.
