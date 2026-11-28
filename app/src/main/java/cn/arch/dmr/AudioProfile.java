package cn.arch.dmr;

/** Immutable audio settings shared by every voice mode. */
final class AudioProfile {
    static final String[] TUNING_KEYS = {
            "flat", "clarity", "bandpass", "deep", "high", "car", "headset"
    };
    static final String[] TUNING_LABELS = {
            "平直", "清晰", "带通", "低沉", "明亮", "车载", "耳机"
    };

    final boolean rxAgc;
    final boolean txAgc;
    final boolean rxVolumeBoost;
    final boolean rxNoiseReduction;
    final boolean txNoiseReduction;
    final float rxGainDb;
    final float txGainDb;
    final float rxAgcTargetDb;
    final float txAgcTargetDb;
    final int rxTuning;
    final int txTuning;

    AudioProfile(boolean rxAgc, boolean txAgc, boolean rxVolumeBoost,
                 boolean rxNoiseReduction, boolean txNoiseReduction,
                 float rxGainDb, float txGainDb, float rxAgcTargetDb,
                 float txAgcTargetDb, int rxTuning, int txTuning) {
        this.rxAgc = rxAgc;
        this.txAgc = txAgc;
        this.rxVolumeBoost = rxVolumeBoost;
        this.rxNoiseReduction = rxNoiseReduction;
        this.txNoiseReduction = txNoiseReduction;
        this.rxGainDb = clampDb(rxGainDb);
        this.txGainDb = clampDb(txGainDb);
        this.rxAgcTargetDb = clampTarget(rxAgcTargetDb);
        this.txAgcTargetDb = clampTarget(txAgcTargetDb);
        this.rxTuning = clampTuning(rxTuning);
        this.txTuning = clampTuning(txTuning);
    }

    static AudioProfile fromPrefs(Prefs p) {
        return new AudioProfile(
                p.rxAgc(), p.txAgc(), p.rxVolumeBoost(),
                p.rxNoiseReduction(), p.txNoiseReduction(),
                p.rxGainDb(), p.txGainDb(),
                p.rxAgcTargetDb(), p.txAgcTargetDb(),
                tuningIndex(p.listeningTuning()), tuningIndex(p.voiceTuning()));
    }

    static int tuningIndex(String key) {
        if (key != null) {
            for (int i = 0; i < TUNING_KEYS.length; i++) {
                if (TUNING_KEYS[i].equals(key)) return i;
            }
        }
        return 0;
    }

    static String tuningLabel(String key) {
        return TUNING_LABELS[tuningIndex(key)];
    }

    private static int clampTuning(int value) {
        return Math.max(0, Math.min(TUNING_KEYS.length - 1, value));
    }

    private static float clampDb(float db) {
        return Math.max(-30f, Math.min(30f, db));
    }

    private static float clampTarget(float db) {
        return Math.max(-12f, Math.min(12f, db));
    }
}