#include <jni.h>
#include <stdint.h>
#include <string.h>
#if defined(__ANDROID_API__) && __ANDROID_API__ < 23
#include <stdio.h>
#undef stderr
/* The prebuilt libopus archive references the API 23 stderr symbol. */
FILE *stderr = &__sF[2];
#endif
#include "opus.h"

#define ARCH_OPUS_MAX_PACKET 4096
#define ARCH_OPUS_MAX_SAMPLES 5760

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

static int decoder_rate(OpusDecoder *decoder, int *rate) {
    if (!decoder || !rate) return 0;
    opus_int32 value = 0;
    if (opus_decoder_ctl(decoder, OPUS_GET_SAMPLE_RATE(&value)) != OPUS_OK) return 0;
    if (!valid_rate((int)value)) return 0;
    *rate = (int)value;
    return 1;
}

static int validate_packet(OpusDecoder *decoder, const unsigned char *packet, int len,
                           int outSamples, int *packetSamples) {
    if (!decoder || !packet || len <= 0 || len > ARCH_OPUS_MAX_PACKET
            || outSamples <= 0 || outSamples > ARCH_OPUS_MAX_SAMPLES) return 0;

    int rate = 0;
    if (!decoder_rate(decoder, &rate) || !valid_samples(rate, outSamples)) return 0;

    int frames = opus_packet_get_nb_frames(packet, len);
    if (frames < 1 || frames > 6) return 0;

    if (opus_packet_get_nb_channels(packet) != 1) return 0;

    int samples = opus_packet_get_nb_samples(packet, len, rate);
    if (samples <= 0 || samples > outSamples) return 0;
    if (packetSamples) *packetSamples = samples;
    return 1;
}

JNIEXPORT jlong JNICALL Java_cn_arch_dmr_ArchOpus_nativeCreateEncoder(
        JNIEnv *e, jclass c, jint rate, jint ch) {
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

JNIEXPORT void JNICALL Java_cn_arch_dmr_ArchOpus_nativeDestroyEncoder(
        JNIEnv *e, jclass c, jlong h) {
    if (h) opus_encoder_destroy((OpusEncoder *)(intptr_t)h);
}

JNIEXPORT void JNICALL Java_cn_arch_dmr_ArchOpus_nativeSetBitrate(
        JNIEnv *e, jclass c, jlong h, jint bps) {
    if (h) opus_encoder_ctl((OpusEncoder *)(intptr_t)h, OPUS_SET_BITRATE(bps));
}

JNIEXPORT jbyteArray JNICALL Java_cn_arch_dmr_ArchOpus_nativeEncode(
        JNIEnv *e, jclass c, jlong h, jshortArray pcm, jint frameSamples) {
    if (!h || !pcm || frameSamples <= 0) return NULL;
    opus_int32 rate = 0;
    if (opus_encoder_ctl((OpusEncoder *)(intptr_t)h, OPUS_GET_SAMPLE_RATE(&rate)) != OPUS_OK
            || !valid_samples(rate, frameSamples)) return NULL;
    if ((*e)->GetArrayLength(e, pcm) < frameSamples) return NULL;

    jshort *p = (*e)->GetShortArrayElements(e, pcm, 0);
    if (!p) return NULL;
    unsigned char out[ARCH_OPUS_MAX_PACKET];
    int n = opus_encode((OpusEncoder *)(intptr_t)h, (const opus_int16 *)p,
                        frameSamples, out, sizeof(out));
    (*e)->ReleaseShortArrayElements(e, pcm, p, JNI_ABORT);
    if (n <= 0 || n > ARCH_OPUS_MAX_PACKET) return NULL;

    jbyteArray a = (*e)->NewByteArray(e, n);
    if (a) (*e)->SetByteArrayRegion(e, a, 0, n, (const jbyte *)out);
    return a;
}

JNIEXPORT jlong JNICALL Java_cn_arch_dmr_ArchOpus_nativeCreateDecoder(
        JNIEnv *e, jclass c, jint rate, jint ch) {
    if (!valid_rate(rate) || (ch != 1 && ch != 2)) return 0;
    int err = 0;
    OpusDecoder *p = opus_decoder_create(rate, ch, &err);
    if (err == OPUS_OK && p) opus_decoder_ctl(p, OPUS_SET_PACKET_LOSS_PERC(10));
    return (err == OPUS_OK && p) ? (jlong)(intptr_t)p : 0;
}

JNIEXPORT void JNICALL Java_cn_arch_dmr_ArchOpus_nativeDestroyDecoder(
        JNIEnv *e, jclass c, jlong h) {
    if (h) opus_decoder_destroy((OpusDecoder *)(intptr_t)h);
}

JNIEXPORT jshortArray JNICALL Java_cn_arch_dmr_ArchOpus_nativeDecode(
        JNIEnv *e, jclass c, jlong h, jbyteArray frame, jint outSamples) {
    if (!h || !frame || outSamples <= 0) return NULL;
    int len = (*e)->GetArrayLength(e, frame);
    if (len <= 0 || len > ARCH_OPUS_MAX_PACKET) return NULL;

    jbyte packet[ARCH_OPUS_MAX_PACKET];
    (*e)->GetByteArrayRegion(e, frame, 0, len, packet);
    int packetSamples = 0;
    if (!validate_packet((OpusDecoder *)(intptr_t)h, (const unsigned char *)packet,
                         len, outSamples, &packetSamples)) return NULL;

    short out[ARCH_OPUS_MAX_SAMPLES];
    int n = opus_decode((OpusDecoder *)(intptr_t)h, (const unsigned char *)packet,
                        len, (opus_int16 *)out, outSamples, 0);
    if (n <= 0 || n > outSamples || n > ARCH_OPUS_MAX_SAMPLES) return NULL;

    jshortArray a = (*e)->NewShortArray(e, n);
    if (a) (*e)->SetShortArrayRegion(e, a, 0, n, (const jshort *)out);
    return a;
}

JNIEXPORT jshortArray JNICALL Java_cn_arch_dmr_ArchOpus_nativeDecodeFec(
        JNIEnv *e, jclass c, jlong h, jbyteArray frame, jint outSamples) {
    if (!h || !frame || outSamples <= 0) return NULL;
    int len = (*e)->GetArrayLength(e, frame);
    if (len <= 0 || len > ARCH_OPUS_MAX_PACKET) return NULL;

    jbyte packet[ARCH_OPUS_MAX_PACKET];
    (*e)->GetByteArrayRegion(e, frame, 0, len, packet);
    int packetSamples = 0;
    if (!validate_packet((OpusDecoder *)(intptr_t)h, (const unsigned char *)packet,
                         len, outSamples, &packetSamples)) return NULL;

    short out[ARCH_OPUS_MAX_SAMPLES];
    int n = opus_decode((OpusDecoder *)(intptr_t)h, (const unsigned char *)packet,
                        len, (opus_int16 *)out, outSamples, 1);
    if (n <= 0 || n > outSamples || n > ARCH_OPUS_MAX_SAMPLES) return NULL;

    jshortArray a = (*e)->NewShortArray(e, n);
    if (a) (*e)->SetShortArrayRegion(e, a, 0, n, (const jshort *)out);
    return a;
}

JNIEXPORT jshortArray JNICALL Java_cn_arch_dmr_ArchOpus_nativeDecodePlc(
        JNIEnv *e, jclass c, jlong h, jint outSamples) {
    if (!h || outSamples <= 0 || outSamples > ARCH_OPUS_MAX_SAMPLES) return NULL;
    int rate = 0;
    if (!decoder_rate((OpusDecoder *)(intptr_t)h, &rate)
            || !valid_samples(rate, outSamples)) return NULL;

    short out[ARCH_OPUS_MAX_SAMPLES];
    int n = opus_decode((OpusDecoder *)(intptr_t)h, NULL, 0,
                        (opus_int16 *)out, outSamples, 0);
    if (n <= 0 || n > outSamples || n > ARCH_OPUS_MAX_SAMPLES) return NULL;

    jshortArray a = (*e)->NewShortArray(e, n);
    if (a) (*e)->SetShortArrayRegion(e, a, 0, n, (const jshort *)out);
    return a;
}


