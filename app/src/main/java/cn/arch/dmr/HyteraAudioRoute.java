package cn.arch.dmr;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;

/**
 * PDC760/PDC680 POC audio HAL routing observed in Hytera HyTalk 3.2.00.310.
 * HyTalk uses AudioManager.setParameters("poc_status=halfduplex") for the
 * half-duplex call and "poc_record_status=1/0" around the transmit floor.
 *
 * This helper only adjusts the vendor HAL route. It does not alter PCM,
 * buffering, Opus, or any audio thread design.
 */
final class HyteraAudioRoute {
    private static final String TAG = "ARCH-HyteraAudio";
    private static java.lang.reflect.Method setParameters;

    private HyteraAudioRoute() {}

    static void enterCall(Context context) {
        set(context, "poc_status=halfduplex");
        set(context, "poc_record_status=0");
    }

    static void setTransmitting(Context context, boolean transmitting) {
        if (transmitting) {
            set(context, "poc_status=halfduplex");
            set(context, "poc_record_status=1");
        } else {
            set(context, "poc_record_status=0");
        }
    }

    static void leaveCall(Context context) {
        set(context, "poc_record_status=0");
        set(context, "poc_status=off");
    }

    private static synchronized void set(Context context, String value) {
        if (context == null) return;
        try {
            if (setParameters == null) {
                setParameters = AudioManager.class.getMethod("setParameters", String.class);
            }
            AudioManager am = (AudioManager) context.getApplicationContext()
                    .getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            setParameters.invoke(am, value);
            Log.i(TAG, "setParameters " + value);
        } catch (Throwable t) {
            Log.w(TAG, "setParameters failed: " + value + " " + t);
        }
    }
}
