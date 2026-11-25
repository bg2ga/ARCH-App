/* ARCH bridge for the upstream MD380 vocoder's 72-bit FEC entry points.
 *
 * The upstream libmd380vocoder.so exposes md380_decode_fec() and
 * md380_encode_fec() but its Android JNI wrapper only exposes a 33-byte DMRD
 * payload decoder.  DMR homebrew and XLX carry the payload as 9-byte voice
 * bursts, so this tiny bridge calls the same native FEC functions directly.
 */
#include <jni.h>
#include <stdint.h>

/* Upstream helper/backend entry points.  The helper is the same path used by
 * the original Java wrapper on arm64; the backend fallback is used on devices
 * where the 32-bit helper is not available. */
extern int  md380_helper_ready(void);
extern int  md380_helper_decode_fec(const uint8_t *ambe72, int16_t *pcm160);
extern int  md380_helper_encode_fec(uint8_t *ambe72, const int16_t *pcm160);
extern int  md380_backend_ready(void);
extern void md380_decode_fec(const uint8_t *ambe72, int16_t *pcm160);
extern void md380_encode_fec(uint8_t *ambe72, const int16_t *pcm160);

JNIEXPORT jshortArray JNICALL
Java_cn_arch_dmr_archzjegwmq_ArchVocoder_nativeDecodeFec9(
        JNIEnv *env, jclass clazz, jbyteArray input) {
    (void)clazz;
    if (input == NULL || (*env)->GetArrayLength(env, input) != 9) return NULL;
    uint8_t in[9];
    int16_t pcm[160];
    (*env)->GetByteArrayRegion(env, input, 0, 9, (jbyte *)in);
    int ok = 0;
    if (md380_helper_ready()) {
        ok = md380_helper_decode_fec(in, pcm);
    } else if (md380_backend_ready()) {
        md380_decode_fec(in, pcm);
        ok = 1;
    }
    if (!ok) return NULL;
    jshortArray out = (*env)->NewShortArray(env, 160);
    if (out != NULL) (*env)->SetShortArrayRegion(env, out, 0, 160, pcm);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_cn_arch_dmr_archzjegwmq_ArchVocoder_nativeEncodeFec9(
        JNIEnv *env, jclass clazz, jshortArray input) {
    (void)clazz;
    if (input == NULL || (*env)->GetArrayLength(env, input) != 160) return NULL;
    int16_t pcm[160];
    uint8_t out9[9] = {0};
    (*env)->GetShortArrayRegion(env, input, 0, 160, pcm);
    int ok = 0;
    if (md380_helper_ready()) {
        ok = md380_helper_encode_fec(out9, pcm);
    } else if (md380_backend_ready()) {
        md380_encode_fec(out9, pcm);
        ok = 1;
    }
    if (!ok) return NULL;
    jbyteArray out = (*env)->NewByteArray(env, 9);
    if (out != NULL) (*env)->SetByteArrayRegion(env, out, 0, 9, (jbyte *)out9);
    return out;
}
