#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include "opus.h"

static int valid_rate(int rate) {
    return rate == 8000 || rate == 12000 || rate == 16000 || rate == 24000 || rate == 48000;
}
static int valid_samples(int rate, int samples) {
    static const int us[] = {2500, 5000, 10000, 20000, 40000, 60000};
    for (unsigned i = 0; i < sizeof(us) / sizeof(us[0]); i++) {
        if ((int64_t)rate * us[i] / 1000000 == samples) return 1;
    }
    return 0;
}

JNIEXPORT jlong JNICALL Java_cn_arch_dmr_FmoOpus_nativeCreateEncoder(JNIEnv *e, jclass c, jint rate, jint ch) {
    if (!valid_rate(rate) || (ch != 1 && ch != 2)) return 0;
    int err = 0;
    OpusEncoder *p = opus_encoder_create(rate, ch, OPUS_APPLICATION_VOIP, &err);
    if (err != OPUS_OK || !p) return 0;
    opus_encoder_ctl(p, OPUS_SET_BITRATE(24000));
    opus_encoder_ctl(p, OPUS_SET_VBR(1));
    opus_encoder_ctl(p, OPUS_SET_COMPLEXITY(6));
    opus_encoder_ctl(p, OPUS_SET_SIGNAL(OPUS_SIGNAL_VOICE));
    opus_encoder_ctl(p, OPUS_SET_INBAND_FEC(1));
    opus_encoder_ctl(p, OPUS_SET_PACKET_LOSS_PERC(10));
    return (jlong)(intptr_t)p;
}
JNIEXPORT void JNICALL Java_cn_arch_dmr_FmoOpus_nativeDestroyEncoder(JNIEnv *e, jclass c, jlong h) {
    if (h) opus_encoder_destroy((OpusEncoder *)(intptr_t)h);
}
JNIEXPORT void JNICALL Java_cn_arch_dmr_FmoOpus_nativeSetBitrate(JNIEnv *e, jclass c, jlong h, jint bps) {
    if (h) opus_encoder_ctl((OpusEncoder *)(intptr_t)h, OPUS_SET_BITRATE(bps));
}
JNIEXPORT jbyteArray JNICALL Java_cn_arch_dmr_FmoOpus_nativeEncode(JNIEnv *e, jclass c, jlong h, jshortArray pcm, jint frameSamples) {
    if (!h || pcm == NULL || frameSamples <= 0) return NULL;
    opus_int32 rate = 0;
    if (opus_encoder_ctl((OpusEncoder *)(intptr_t)h, OPUS_GET_SAMPLE_RATE(&rate)) != OPUS_OK
            || !valid_samples(rate, frameSamples)) return NULL;
    if ((*e)->GetArrayLength(e, pcm) < frameSamples) return NULL;
    jshort *p = (*e)->GetShortArrayElements(e, pcm, 0);
    if (!p) return NULL;
    unsigned char out[4096];
    int n = opus_encode((OpusEncoder *)(intptr_t)h, (const opus_int16 *)p, frameSamples, out, sizeof(out));
    (*e)->ReleaseShortArrayElements(e, pcm, p, JNI_ABORT);
    if (n < 0) return NULL;
    jbyteArray a = (*e)->NewByteArray(e, n);
    if (a) (*e)->SetByteArrayRegion(e, a, 0, n, (const jbyte *)out);
    return a;
}
JNIEXPORT jlong JNICALL Java_cn_arch_dmr_FmoOpus_nativeCreateDecoder(JNIEnv *e, jclass c, jint rate, jint ch) {
    if (!valid_rate(rate) || (ch != 1 && ch != 2)) return 0;
    int err = 0;
    OpusDecoder *p = opus_decoder_create(rate, ch, &err);
    if (err == OPUS_OK && p) opus_decoder_ctl(p, OPUS_SET_PACKET_LOSS_PERC(10));
    return (err == OPUS_OK && p) ? (jlong)(intptr_t)p : 0;
}
JNIEXPORT void JNICALL Java_cn_arch_dmr_FmoOpus_nativeDestroyDecoder(JNIEnv *e, jclass c, jlong h) {
    if (h) opus_decoder_destroy((OpusDecoder *)(intptr_t)h);
}
JNIEXPORT jshortArray JNICALL Java_cn_arch_dmr_FmoOpus_nativeDecode(JNIEnv *e, jclass c, jlong h, jbyteArray frame, jint outSamples) {
    if (!h || frame == NULL || outSamples <= 0) return NULL;
    opus_int32 rate = 0;
    if (opus_decoder_ctl((OpusDecoder *)(intptr_t)h, OPUS_GET_SAMPLE_RATE(&rate)) != OPUS_OK
            || !valid_samples(rate, outSamples)) return NULL;
    int len = (*e)->GetArrayLength(e, frame);
    if (len <= 0 || len > 4096) return NULL;
    jbyte *b = (*e)->GetByteArrayElements(e, frame, 0);
    if (!b) return NULL;
    jshortArray a = (*e)->NewShortArray(e, outSamples);
    jshort *out = a ? (*e)->GetShortArrayElements(e, a, 0) : NULL;
    if (!out) {
        (*e)->ReleaseByteArrayElements(e, frame, b, JNI_ABORT);
        if (a) (*e)->DeleteLocalRef(e, a);
        return NULL;
    }
    int n = opus_decode((OpusDecoder *)(intptr_t)h, (const unsigned char *)b, len,
                        (opus_int16 *)out, outSamples, 0);
    (*e)->ReleaseByteArrayElements(e, frame, b, JNI_ABORT);
    if (n < 0) {
        (*e)->ReleaseShortArrayElements(e, a, out, JNI_ABORT);
        (*e)->DeleteLocalRef(e, a);
        return NULL;
    }
    (*e)->ReleaseShortArrayElements(e, a, out, 0);
    return a;
}

JNIEXPORT jshortArray JNICALL Java_cn_arch_dmr_FmoOpus_nativeDecodeFec(JNIEnv *e, jclass c, jlong h, jbyteArray frame, jint outSamples) {
    if (!h || frame == NULL || outSamples <= 0) return NULL;
    opus_int32 rate = 0;
    if (opus_decoder_ctl((OpusDecoder *)(intptr_t)h, OPUS_GET_SAMPLE_RATE(&rate)) != OPUS_OK
            || !valid_samples(rate, outSamples)) return NULL;
    int len = (*e)->GetArrayLength(e, frame);
    if (len <= 0 || len > 4096) return NULL;
    jbyte *b = (*e)->GetByteArrayElements(e, frame, 0);
    if (!b) return NULL;
    jshortArray a = (*e)->NewShortArray(e, outSamples);
    jshort *out = a ? (*e)->GetShortArrayElements(e, a, 0) : NULL;
    if (!out) { (*e)->ReleaseByteArrayElements(e, frame, b, JNI_ABORT); if (a) (*e)->DeleteLocalRef(e, a); return NULL; }
    int n = opus_decode((OpusDecoder *)(intptr_t)h, (const unsigned char *)b, len,
                        (opus_int16 *)out, outSamples, 1);
    (*e)->ReleaseByteArrayElements(e, frame, b, JNI_ABORT);
    if (n < 0) { (*e)->ReleaseShortArrayElements(e, a, out, JNI_ABORT); (*e)->DeleteLocalRef(e, a); return NULL; }
    (*e)->ReleaseShortArrayElements(e, a, out, 0);
    return a;
}

JNIEXPORT jshortArray JNICALL Java_cn_arch_dmr_FmoOpus_nativeDecodePlc(JNIEnv *e, jclass c, jlong h, jint outSamples) {
    if (!h || outSamples <= 0) return NULL;
    opus_int32 rate = 0;
    if (opus_decoder_ctl((OpusDecoder *)(intptr_t)h, OPUS_GET_SAMPLE_RATE(&rate)) != OPUS_OK
            || !valid_samples(rate, outSamples)) return NULL;
    jshortArray a = (*e)->NewShortArray(e, outSamples);
    jshort *out = a ? (*e)->GetShortArrayElements(e, a, 0) : NULL;
    if (!out) {
        if (a) (*e)->DeleteLocalRef(e, a);
        return NULL;
    }
    int n = opus_decode((OpusDecoder *)(intptr_t)h, NULL, 0,
                        (opus_int16 *)out, outSamples, 0);
    if (n < 0) {
        (*e)->ReleaseShortArrayElements(e, a, out, JNI_ABORT);
        (*e)->DeleteLocalRef(e, a);
        return NULL;
    }
    (*e)->ReleaseShortArrayElements(e, a, out, 0);
    return a;
}
