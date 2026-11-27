/* ARCH: blip25 JNI bridge with strict array-length guards. */
#include <jni.h>
#include <stdint.h>

extern void  *arch_blip25_create(void);
extern void   arch_blip25_destroy(void *v);
extern int    arch_blip25_reset(void *v);
extern int    arch_blip25_encode(void *v, const int16_t *pcm, uint8_t *out9);
extern int    arch_blip25_decode(void *v, const uint8_t *in9, int16_t *pcm);

JNIEXPORT jlong JNICALL Java_cn_arch_dmr_Vocoder_nativeCreate(JNIEnv *e, jclass c) {
    return (jlong)(intptr_t)arch_blip25_create();
}
JNIEXPORT void JNICALL Java_cn_arch_dmr_Vocoder_nativeDestroy(JNIEnv *e, jclass c, jlong h) {
    if (h) arch_blip25_destroy((void *)(intptr_t)h);
}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_Vocoder_nativeReset(JNIEnv *e, jclass c, jlong h) {
    return h ? arch_blip25_reset((void *)(intptr_t)h) : -1;
}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_Vocoder_nativeEncode(
        JNIEnv *e, jclass c, jlong h, jshortArray pcm, jbyteArray out) {
    if (!h || pcm == NULL || out == NULL) return -1;
    if ((*e)->GetArrayLength(e, pcm) != 160 || (*e)->GetArrayLength(e, out) != 9) return -1;
    jshort *p = (*e)->GetShortArrayElements(e, pcm, 0);
    jbyte  *o = (*e)->GetByteArrayElements(e, out, 0);
    if (!p || !o) {
        if (p) (*e)->ReleaseShortArrayElements(e, pcm, p, JNI_ABORT);
        if (o) (*e)->ReleaseByteArrayElements(e, out, o, JNI_ABORT);
        return -1;
    }
    int rc = arch_blip25_encode((void *)(intptr_t)h, (const int16_t *)p, (uint8_t *)o);
    (*e)->ReleaseShortArrayElements(e, pcm, p, JNI_ABORT);
    (*e)->ReleaseByteArrayElements(e, out, o, rc == 0 ? 0 : JNI_ABORT);
    return rc;
}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_Vocoder_nativeDecode(
        JNIEnv *e, jclass c, jlong h, jbyteArray in, jshortArray pcm) {
    if (!h || in == NULL || pcm == NULL) return -1;
    if ((*e)->GetArrayLength(e, in) != 9 || (*e)->GetArrayLength(e, pcm) != 160) return -1;
    jbyte  *i = (*e)->GetByteArrayElements(e, in, 0);
    jshort *p = (*e)->GetShortArrayElements(e, pcm, 0);
    if (!i || !p) {
        if (i) (*e)->ReleaseByteArrayElements(e, in, i, JNI_ABORT);
        if (p) (*e)->ReleaseShortArrayElements(e, pcm, p, JNI_ABORT);
        return -1;
    }
    int rc = arch_blip25_decode((void *)(intptr_t)h, (const uint8_t *)i, (int16_t *)p);
    (*e)->ReleaseByteArrayElements(e, in, i, JNI_ABORT);
    (*e)->ReleaseShortArrayElements(e, pcm, p, rc == 0 ? 0 : JNI_ABORT);
    return rc;
}
