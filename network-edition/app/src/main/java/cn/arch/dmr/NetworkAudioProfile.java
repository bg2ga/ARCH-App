package cn.arch.dmr;

import android.media.AudioManager;

/** Fixed HyTalk-derived audio profiles for network/POC mode. No dynamic AGC. */
final class NetworkAudioProfile {
    private NetworkAudioProfile() {}

    static int streamType(HyteraDeviceProfile hw, String route) {
        if ("earpiece".equals(route) || "bluetooth".equals(route)) return AudioManager.STREAM_VOICE_CALL;
        if (hw != null && (hw.isP50Pro() || hw.isPnc370() || hw.isVm780())) return AudioManager.STREAM_VOICE_CALL;
        return AudioManager.STREAM_MUSIC;
    }

    static float rxTrim(HyteraDeviceProfile hw) {
        if (hw == null) return 1f;
        if (hw.isPnc370()) return 0.25f;
        if (hw.isVm780()) return 0.50f;
        if (hw.isP50Pro()) return 2.00f;
        return 1f;
    }

    static float txTrim(HyteraDeviceProfile hw) { return 1f; }

    static void prepare(AudioManager am, HyteraDeviceProfile hw, String route) {
        if (am == null) return;
        boolean earpiece = "earpiece".equals(route);
        boolean bluetooth = "bluetooth".equals(route);
        try {
            if (bluetooth) {
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(false);
                am.startBluetoothSco();
                am.setBluetoothScoOn(true);
            } else {
                am.stopBluetoothSco();
                am.setBluetoothScoOn(false);
                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                am.setSpeakerphoneOn(!earpiece);
            }
        } catch (Throwable ignored) {}
        try { am.setParameters("poc_status=halfduplex"); } catch (Throwable ignored) {}
        if (hw != null && hw.isPnc380()) {
            try { am.setParameters("dream_mono=1"); } catch (Throwable ignored) {}
        }
        android.util.Log.i("ARCH-AUDIO-PROFILE", "model=" + (hw == null ? "generic" : hw.family)
                + " stream=" + streamType(hw, route) + " rxTrim=" + rxTrim(hw)
                + " txTrim=" + txTrim(hw) + " mode=" + am.getMode()
                + " speaker=" + am.isSpeakerphoneOn() + " poc=halfduplex"
                + (hw != null && hw.isPnc380() ? " dream_mono=1" : ""));
    }

    static void clearTerminalParameters(AudioManager am, HyteraDeviceProfile hw) {
        if (am == null) return;
        try { am.setParameters("poc_status=off"); } catch (Throwable ignored) {}
        if (hw != null && hw.isPnc380()) {
            try { am.setParameters("dream_mono=0"); } catch (Throwable ignored) {}
        }
        android.util.Log.i("ARCH-AUDIO-PROFILE", "clear terminal POC parameters model="
                + (hw == null ? "generic" : hw.family));
    }

    static void restore(AudioManager am, HyteraDeviceProfile hw) {
        if (am == null) return;
        try { am.setParameters("poc_status=off"); } catch (Throwable ignored) {}
        if (hw != null && hw.isPnc380()) {
            try { am.setParameters("dream_mono=0"); } catch (Throwable ignored) {}
        }
        try { am.stopBluetoothSco(); am.setBluetoothScoOn(false); } catch (Throwable ignored) {}
        try { am.setSpeakerphoneOn(false); } catch (Throwable ignored) {}
        try { am.setMode(AudioManager.MODE_NORMAL); } catch (Throwable ignored) {}
    }
}
