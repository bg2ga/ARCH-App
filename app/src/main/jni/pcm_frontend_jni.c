#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <math.h>
#include <speex/speex_resampler.h>
#include <speex/speex_preprocess.h>

#define PCM_MAX_INPUT 2400

static int16_t clamp16(int v);

typedef struct {
    SpeexResamplerState *resampler;
    SpeexPreprocessState *preprocess;
    float dc_prev_in;
    float dc_prev_out;
    int16_t in16[PCM_MAX_INPUT];
    int16_t out8[PCM_MAX_INPUT];
    int16_t frame[160];
    int frame_len;
} pcm8k_state;

JNIEXPORT jlong JNICALL Java_cn_arch_dmr_PcmFrontEnd_nativeCreate(JNIEnv*, jclass) {
    pcm8k_state *st = (pcm8k_state*)calloc(1, sizeof(pcm8k_state));
    if (!st) return 0;
    int err = 0;
    st->resampler = speex_resampler_init(1, 48000, 8000, 6, &err);
    st->preprocess = speex_preprocess_state_init(160, 8000);
    if (st->preprocess) {
        int agc = 1;
        speex_preprocess_ctl(st->preprocess, SPEEX_PREPROCESS_SET_AGC, &agc);
    }
    if (!st->resampler || !st->preprocess) {
        if (st->resampler) speex_resampler_destroy(st->resampler);
        if (st->preprocess) speex_preprocess_state_destroy(st->preprocess);
        free(st);
        return 0;
    }
    return (jlong)(intptr_t)st;
}

JNIEXPORT void JNICALL Java_cn_arch_dmr_PcmFrontEnd_nativeDestroy(JNIEnv*, jclass, jlong h) {
    pcm8k_state *st = (pcm8k_state*)(intptr_t)h;
    if (!st) return;
    if (st->resampler) speex_resampler_destroy(st->resampler);
    if (st->preprocess) speex_preprocess_state_destroy(st->preprocess);
    free(st);
}

JNIEXPORT void JNICALL Java_cn_arch_dmr_PcmFrontEnd_nativeReset(JNIEnv*, jclass, jlong h) {
    pcm8k_state *st = (pcm8k_state*)(intptr_t)h;
    if (!st) return;
    if (st->resampler) speex_resampler_destroy(st->resampler);
    if (st->preprocess) speex_preprocess_state_destroy(st->preprocess);
    int err = 0;
    st->resampler = speex_resampler_init(1, 48000, 8000, 6, &err);
    st->preprocess = speex_preprocess_state_init(160, 8000);
    if (st->preprocess) {
        int agc = 1;
        speex_preprocess_ctl(st->preprocess, SPEEX_PREPROCESS_SET_AGC, &agc);
    }
    st->dc_prev_in = 0.0f;
    st->dc_prev_out = 0.0f;
    st->frame_len = 0;
}

JNIEXPORT jshortArray JNICALL Java_cn_arch_dmr_PcmFrontEnd_nativeProcess(
        JNIEnv* env, jclass, jlong h, jshortArray pcm, jint sampleRate) {
    (void)sampleRate;
    pcm8k_state *st = (pcm8k_state*)(intptr_t)h;
    if (!st || !st->resampler || !st->preprocess || !pcm) return NULL;
    jsize n = (*env)->GetArrayLength(env, pcm);
    if (n <= 0) return NULL;
    if (n > PCM_MAX_INPUT) n = PCM_MAX_INPUT;
    jshort *src = (*env)->GetShortArrayElements(env, pcm, 0);
    if (!src) return NULL;
    for (jsize i = 0; i < n; i++) {
        float x = (float)src[i];
        float y = x - st->dc_prev_in + 0.995f * st->dc_prev_out;
        st->dc_prev_in = x;
        st->dc_prev_out = y;
        st->in16[i] = clamp16((int)lrintf(y));
    }
    (*env)->ReleaseShortArrayElements(env, pcm, src, JNI_ABORT);

    spx_uint32_t in_len = (spx_uint32_t)n;
    spx_uint32_t out_len = PCM_MAX_INPUT;
    int err = speex_resampler_process_int(st->resampler, 0, st->in16, &in_len, st->out8, &out_len);
    if (err != 0 || out_len == 0) return NULL;
    for (spx_uint32_t i = 0; i < out_len && st->frame_len < 160; i++) {
        st->frame[st->frame_len++] = st->out8[i];
    }
    if (st->frame_len < 160) return NULL;
    speex_preprocess_run(st->preprocess, st->frame);
    st->frame_len = 0;
    jshortArray result = (*env)->NewShortArray(env, 160);
    if (!result) return NULL;
    (*env)->SetShortArrayRegion(env, result, 0, 160, (const jshort*)st->frame);
    return result;
}

static int16_t clamp16(int v) {
    if (v > 32767) return 32767;
    if (v < -32768) return -32768;
    return (int16_t)v;
}
