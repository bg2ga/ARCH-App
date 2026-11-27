#include <jni.h>
#include <stdint.h>
#include <stddef.h>
#include "mbe/mbevocoder.h"
#include "imbe_vocoder/imbe_vocoder.h"

extern "C" {

JNIEXPORT jlong JNICALL Java_cn_arch_dmr_DStarVocoder_nativeCreate(JNIEnv*, jclass) {
    try { return (jlong)(intptr_t)new MBEVocoder(); } catch (...) { return 0; }
}

JNIEXPORT void JNICALL Java_cn_arch_dmr_DStarVocoder_nativeDestroy(JNIEnv*, jclass, jlong h) {
    if (h) delete (MBEVocoder*)(intptr_t)h;
}

JNIEXPORT jint JNICALL Java_cn_arch_dmr_DStarVocoder_nativeReset(JNIEnv*, jclass, jlong h) {
    if (!h) return -1;
    // MBEVocoder has no explicit reset API; recreation is handled by the Java owner.
    return 0;
}

JNIEXPORT jint JNICALL Java_cn_arch_dmr_DStarVocoder_nativeEncode(
        JNIEnv* env, jclass, jlong h, jshortArray pcm, jbyteArray out) {
    if (!h || !pcm || !out) return -1;
    if (env->GetArrayLength(pcm) != 160 || env->GetArrayLength(out) != 9) return -2;
    jshort* p = env->GetShortArrayElements(pcm, 0);
    jbyte* o = env->GetByteArrayElements(out, 0);
    if (!p || !o) {
        if (p) env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
        if (o) env->ReleaseByteArrayElements(out, o, JNI_ABORT);
        return -3;
    }
    try { ((MBEVocoder*)(intptr_t)h)->encode_2400x1200((int16_t*)p, (uint8_t*)o); }
    catch (...) {
        env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
        env->ReleaseByteArrayElements(out, o, JNI_ABORT);
        return -4;
    }
    env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
    env->ReleaseByteArrayElements(out, o, 0);
    return 0;
}

JNIEXPORT jint JNICALL Java_cn_arch_dmr_DStarVocoder_nativeDecode(
        JNIEnv* env, jclass, jlong h, jbyteArray in, jshortArray pcm) {
    if (!h || !in || !pcm) return -1;
    if (env->GetArrayLength(in) != 9 || env->GetArrayLength(pcm) != 160) return -2;
    jbyte* i = env->GetByteArrayElements(in, 0);
    jshort* p = env->GetShortArrayElements(pcm, 0);
    if (!i || !p) {
        if (i) env->ReleaseByteArrayElements(in, i, JNI_ABORT);
        if (p) env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
        return -3;
    }
    try { ((MBEVocoder*)(intptr_t)h)->decode_2400x1200((int16_t*)p, (uint8_t*)i); }
    catch (...) {
        env->ReleaseByteArrayElements(in, i, JNI_ABORT);
        env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
        return -4;
    }
    env->ReleaseByteArrayElements(in, i, JNI_ABORT);
    env->ReleaseShortArrayElements(pcm, p, 0);
    return 0;
}


JNIEXPORT jlong JNICALL Java_cn_arch_dmr_P25Vocoder_nativeCreate(JNIEnv*, jclass) {
    try { return (jlong)(intptr_t)new imbe_vocoder(); } catch (...) { return 0; }
}

JNIEXPORT void JNICALL Java_cn_arch_dmr_P25Vocoder_nativeDestroy(JNIEnv*, jclass, jlong h) {
    if (h) delete (imbe_vocoder*)(intptr_t)h;
}

JNIEXPORT jint JNICALL Java_cn_arch_dmr_P25Vocoder_nativeEncode(
        JNIEnv* env, jclass, jlong h, jshortArray pcm, jbyteArray out) {
    if (!h || !pcm || !out) return -1;
    if (env->GetArrayLength(pcm) != 160 || env->GetArrayLength(out) != 11) return -2;
    jshort* p = env->GetShortArrayElements(pcm, 0);
    jbyte* o = env->GetByteArrayElements(out, 0);
    if (!p || !o) {
        if (p) env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
        if (o) env->ReleaseByteArrayElements(out, o, JNI_ABORT);
        return -3;
    }
    try { ((imbe_vocoder*)(intptr_t)h)->encode_4400((int16_t*)p, (uint8_t*)o); }
    catch (...) {
        env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
        env->ReleaseByteArrayElements(out, o, JNI_ABORT);
        return -4;
    }
    env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
    env->ReleaseByteArrayElements(out, o, 0);
    return 0;
}

JNIEXPORT jint JNICALL Java_cn_arch_dmr_P25Vocoder_nativeDecode(
        JNIEnv* env, jclass, jlong h, jbyteArray in, jshortArray pcm) {
    if (!h || !in || !pcm) return -1;
    if (env->GetArrayLength(in) != 11 || env->GetArrayLength(pcm) != 160) return -2;
    jbyte* i = env->GetByteArrayElements(in, 0);
    jshort* p = env->GetShortArrayElements(pcm, 0);
    if (!i || !p) {
        if (i) env->ReleaseByteArrayElements(in, i, JNI_ABORT);
        if (p) env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
        return -3;
    }
    try { ((imbe_vocoder*)(intptr_t)h)->decode_4400((int16_t*)p, (uint8_t*)i); }
    catch (...) {
        env->ReleaseByteArrayElements(in, i, JNI_ABORT);
        env->ReleaseShortArrayElements(pcm, p, JNI_ABORT);
        return -4;
    }
    env->ReleaseByteArrayElements(in, i, JNI_ABORT);
    env->ReleaseShortArrayElements(pcm, p, 0);
    return 0;
}

}