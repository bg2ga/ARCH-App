#include <jni.h>

/* Keep the existing MD380 binary backend while exposing ARCH-owned JNI names. */
extern jshortArray JNICALL Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeDecode(JNIEnv*, jclass, jbyteArray);
extern jshortArray JNICALL Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeDecodeDmrdPayload(JNIEnv*, jclass, jbyteArray);
extern jbyteArray JNICALL Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeEncodeFec(JNIEnv*, jclass, jshortArray);
extern jboolean JNICALL Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeInit(JNIEnv*, jclass, jstring, jstring, jstring);
extern jint JNICALL Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativePinToFastCores(JNIEnv*, jclass);
extern jint JNICALL Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeVocoderMode(JNIEnv*, jclass);

JNIEXPORT jshortArray JNICALL Java_cn_arch_dmr_vocoder_Md380Vocoder_nativeDecode(JNIEnv* e, jclass c, jbyteArray a) {
    return Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeDecode(e, c, a);
}
JNIEXPORT jshortArray JNICALL Java_cn_arch_dmr_vocoder_Md380Vocoder_nativeDecodeDmrdPayload(JNIEnv* e, jclass c, jbyteArray a) {
    return Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeDecodeDmrdPayload(e, c, a);
}
JNIEXPORT jbyteArray JNICALL Java_cn_arch_dmr_vocoder_Md380Vocoder_nativeEncodeFec(JNIEnv* e, jclass c, jshortArray a) {
    return Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeEncodeFec(e, c, a);
}
JNIEXPORT jboolean JNICALL Java_cn_arch_dmr_vocoder_Md380Vocoder_nativeInit(JNIEnv* e, jclass c, jstring a, jstring b, jstring d) {
    return Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeInit(e, c, a, b, d);
}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_vocoder_Md380Vocoder_nativePinToFastCores(JNIEnv* e, jclass c) {
    return Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativePinToFastCores(e, c);
}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_vocoder_Md380Vocoder_nativeVocoderMode(JNIEnv* e, jclass c) {
    return Java_com_z3_dmr_net_vocoder_Md380Vocoder_nativeVocoderMode(e, c);
}
