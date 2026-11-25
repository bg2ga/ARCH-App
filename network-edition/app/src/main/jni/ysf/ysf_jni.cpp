#include <jni.h>
#include <stdint.h>
#include <string.h>
#include "ysf_tables.h"
#include "YSFFICH.h"
#include "YSFConvolution.h"
#include "CRCenc.h"
#include "Golay24128.h"
#include "chamming.h"
#include "../dstar/mbe/mbevocoder.h"
#include "../dstar/mbe/arch_blip25.h"
#define YSF_FRAME_LEN 155
#define YSF_SYNC_LEN 5
#define YSF_FICH_LEN 25
static const uint8_t YSF_SYNC[5] = {0xD4U, 0x71U, 0xC9U, 0x63U, 0x4DU};
struct YsfCtx { MBEVocoder voc; void* blip25; };
static void writeDataFRModeData1(const uint8_t* dt, uint8_t* data) {
 data+=YSF_SYNC_LEN+YSF_FICH_LEN; uint8_t output[25]; for(int i=0;i<20;i++)output[i]=dt[i]^WHITENING_DATA[i]; CCRC::addCCITT162(output,22U); output[22]=0;
 uint8_t convolved[45]; CYSFConvolution conv; conv.encode(output,convolved,180U); uint8_t bytes[45]; memset(bytes,0,sizeof(bytes)); uint32_t j=0;
 for(uint32_t i=0;i<180U;i++){uint32_t n=INTERLEAVE_TABLE_9_20[i];bool s0=READ_BIT(convolved,j)!=0;j++;bool s1=READ_BIT(convolved,j)!=0;j++;WRITE_BIT(bytes,n,s0);n++;WRITE_BIT(bytes,n,s1);} uint8_t* p1=data;uint8_t* p2=bytes;for(int i=0;i<5;i++){memcpy(p1,p2,9);p1+=18;p2+=9;}
}
static void writeDataFRModeData2(const uint8_t* dt, uint8_t* data) {
 data+=YSF_SYNC_LEN+YSF_FICH_LEN; uint8_t output[25]; for(int i=0;i<20;i++)output[i]=dt[i]^WHITENING_DATA[i]; CCRC::addCCITT162(output,22U); output[22]=0;
 uint8_t convolved[45]; CYSFConvolution conv; conv.encode(output,convolved,180U); uint8_t bytes[45]; memset(bytes,0,sizeof(bytes)); uint32_t j=0;
 for(uint32_t i=0;i<180U;i++){uint32_t n=INTERLEAVE_TABLE_9_20[i];bool s0=READ_BIT(convolved,j)!=0;j++;bool s1=READ_BIT(convolved,j)!=0;j++;WRITE_BIT(bytes,n,s0);n++;WRITE_BIT(bytes,n,s1);} uint8_t* p1=data+9;uint8_t* p2=bytes;for(int i=0;i<5;i++){memcpy(p1,p2,9);p1+=18;p2+=9;}
}
static void ysf_scramble(uint8_t* buf,int len){static const uint8_t s[180]={1,0,0,1,0,0,1,1,1,1,0,1,0,1,1,1,0,1,0,1,0,0,0,1,0,0,1,0,0,0,0,1,1,0,0,1,1,1,0,0,0,0,1,0,1,1,1,1,0,1,1,0,1,1,0,0,1,1,0,1,0,0,0,0,1,1,1,0,1,1,1,1,0,0,0,0,1,1,1,1,1,1,1,1,1,0,0,0,0,0,1,1,1,1,0,1,1,1,1,1,0,0,0,1,0,1,1,1,0,0,1,1,0,0,1,0,0,0,0,0,1,0,0,1,0,1,0,0,1,1,1,0,1,1,0,1,0,0,0,1,1,1,1,0,0,1,1,1,1,1,0,0,1,1,0,1,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0};for(int i=0;i<len;i++)buf[i]^=s[i];}static void generate_vch_vd2(const uint8_t* a, uint8_t* vch) {
    static const uint8_t RS1_S[20] = {147,215,81,33,156,47,108,208,239,15,248,61,241,115,32,148,237,30,124,216};
    uint8_t bits[104];
    memset(bits, 0, sizeof(bits));
    for (int i = 0; i < 27; i++) {
        uint8_t b = a[i] ? 1 : 0;
        bits[i * 3] = b;
        bits[i * 3 + 1] = b;
        bits[i * 3 + 2] = b;
    }
    for (int i = 0; i < 22; i++) bits[81 + i] = a[27 + i] ? 1 : 0;
    bits[103] = 0;
    uint8_t packed[13];
    memset(packed, 0, sizeof(packed));
    for (int i = 0; i < 104; i++) if (bits[i]) packed[i >> 3] |= (uint8_t)(0x80U >> (i & 7));
    for (int i = 0; i < 13; i++) packed[i] ^= RS1_S[i];
    memset(vch, 0, 13);
    for (int i = 0; i < 104; i++) {
        int pos = ((i % 26) * 4) + (i / 26);
        uint8_t bit = (uint8_t)((packed[i >> 3] >> (7 - (i & 7))) & 1U);
        int idx = pos >> 3;
        uint8_t mask = (uint8_t)(0x80U >> (pos & 7));
        if (bit) vch[idx] |= mask;
        else vch[idx] &= (uint8_t)~mask;
    }
}
static void writeVDMode2Data(uint8_t* data,const uint8_t* dt,const uint8_t* ambe35){data+=YSF_SYNC_LEN+YSF_FICH_LEN;uint8_t tmp[13];memcpy(tmp,dt,10);for(int i=0;i<10;i++)tmp[i]^=WHITENING_DATA[i];CCRC::addCCITT162(tmp,12U);tmp[12]=0;uint8_t convolved[25];CYSFConvolution conv;conv.start();conv.encode(tmp,convolved,100U);uint8_t bytes[25];memset(bytes,0,sizeof(bytes));uint32_t j=0;for(uint32_t i=0;i<100U;i++){uint32_t n=INTERLEAVE_TABLE_5_20[i];bool s0=READ_BIT(convolved,j)!=0;j++;bool s1=READ_BIT(convolved,j)!=0;j++;WRITE_BIT(bytes,n,s0);n++;WRITE_BIT(bytes,n,s1);}uint8_t* p1=data;uint8_t* p2=bytes;for(int i=0;i<5;i++){memcpy(p1,p2,5);uint8_t a[56];const uint8_t* d=ambe35+7*i;for(int k=0;k<7;k++)for(int b=0;b<8;b++)a[8*k+b]=(d[k]>>(7-b))&1;uint8_t vch[13];generate_vch_vd2(a,vch);memcpy(p1+5,vch,13);p1+=18;p2+=5;}}
static void encode_header(uint8_t* out,const uint8_t* call,uint32_t txcnt,bool eot){memset(out,0,YSF_FRAME_LEN);memcpy(out,"YSFD",4);memcpy(out+4,call,10);memcpy(out+14,call,10);memcpy(out+24,"ALL       ",10);out[34]=eot?(uint8_t)(((txcnt&0x7f)<<1)|1U):0;uint8_t* p=out+35;memcpy(p,YSF_SYNC,5);CYSFFICH fich;fich.setFI(eot?2U:0U);fich.setCS(2U);fich.setCM(0U);fich.setBN(0U);fich.setBT(0U);fich.setFN(0U);fich.setFT(6U);fich.setDev(false);fich.setMR(0U);fich.setVoIP(false);fich.setDT(2U);fich.setSQL(false);fich.setSQ(0U);fich.encode(p);uint8_t csd1[20],csd2[20];memset(csd1,'*',10);memcpy(csd1+10,call,10);memcpy(csd2,call,10);memcpy(csd2+10,call,10);writeDataFRModeData1(csd1,p);writeDataFRModeData2(csd2,p);}
static void encode_voice(uint8_t* out,const uint8_t* call,const uint8_t* ambe35,uint32_t txcnt){memset(out,0,YSF_FRAME_LEN);memcpy(out,"YSFD",4);memcpy(out+4,call,10);memcpy(out+14,call,10);memcpy(out+24,"ALL       ",10);out[34]=(uint8_t)((txcnt&0x7f)<<1);uint8_t* p=out+35;memcpy(p,YSF_SYNC,5);int fn=(int)((txcnt-1U)%7U);CYSFFICH fich;fich.setFI(1U);fich.setCS(2U);fich.setCM(0U);fich.setBN(0U);fich.setBT(0U);fich.setFN((uint8_t)fn);fich.setFT(6U);fich.setDev(false);fich.setMR(0U);fich.setVoIP(false);fich.setDT(2U);fich.setSQL(false);fich.setSQ(0U);fich.encode(p);static const uint8_t ft70d1[10]={0x01,0x22,0x61,0x5f,0x2b,0x03,0x11,0x00,0x00,0x00};static const uint8_t dt2[10]={0x00,0x00,0x00,0x00,0x6C,0x20,0x1C,0x20,0x03,0x08};static const uint8_t stars[10]={'*','*','*','*','*','*','*','*','*','*'};const uint8_t* dt=(const uint8_t*)"          ";switch(fn){case 0:dt=stars;break;case 1:case 2:case 3:dt=call;break;case 6:dt=ft70d1;break;case 7:dt=dt2;break;}writeVDMode2Data(p,dt,ambe35);}
static bool decode_header(const uint8_t* p,uint8_t* src,uint8_t* dst){p+=YSF_SYNC_LEN+YSF_FICH_LEN;uint8_t dch[45];const uint8_t* p1=p;uint8_t* p2=dch;for(int i=0;i<5;i++){memcpy(p2,p1,9);p1+=18;p2+=9;}CYSFConvolution conv;conv.start();for(uint32_t i=0;i<180U;i++){uint32_t n=INTERLEAVE_TABLE_9_20[i];uint8_t s0=READ_BIT(dch,n)?1U:0U;n++;uint8_t s1=READ_BIT(dch,n)?1U:0U;conv.decode(s0,s1);}uint8_t o[23];conv.chainback(o,176U);if(!CCRC::checkCCITT162(o,22U))return false;for(int i=0;i<20;i++)o[i]^=WHITENING_DATA[i];memcpy(dst,o,10);memcpy(src,o+10,10);return true;}
static bool decode_vd2(const uint8_t* p,uint8_t* dt){uint8_t dch[25];const uint8_t* p1=p;uint8_t* p2=dch;for(int i=0;i<5;i++){memcpy(p2,p1,5);p1+=18;p2+=5;}CYSFConvolution conv;conv.start();for(uint32_t i=0;i<100U;i++){uint32_t n=INTERLEAVE_TABLE_5_20[i];uint8_t s0=READ_BIT(dch,n)?1U:0U;n++;uint8_t s1=READ_BIT(dch,n)?1U:0U;conv.decode(s0,s1);}uint8_t o[13];conv.chainback(o,96U);if(!CCRC::checkCCITT162(o,12U))return false;for(int i=0;i<10;i++)o[i]^=WHITENING_DATA[i];memcpy(dt,o,10);return true;}
static bool decode_voice(const uint8_t* p,uint8_t* ambe35,uint8_t* src,uint8_t* dst,int* fi,int* fn){CYSFFICH f;if(!f.decode(p))return false;*fi=f.getFI();*fn=f.getFN();const uint8_t* base=p+YSF_SYNC_LEN+YSF_FICH_LEN;uint8_t dch[10];if(!decode_vd2(base,dch))return false;if(*fn==0)memcpy(dst,dch,10);else if(*fn==1)memcpy(src,dch,10);uint32_t off=40U;memset(ambe35,0,35);for(int j=0;j<5;j++,off+=144U){uint8_t vch[13];memset(vch,0,13);for(uint32_t i=0;i<104U;i++){uint32_t n=INTERLEAVE_TABLE_26_4[i];WRITE_BIT(vch,i,READ_BIT(base,off+n));}for(int i=0;i<13;i++)vch[i]^=WHITENING_DATA[i];uint32_t a=0,b=0,c=0;for(int i=0;i<12;i++){a<<=1;if(READ_BIT(vch,3*i+1))a|=1;}for(int i=0;i<12;i++){b<<=1;if(READ_BIT(vch,3*(i+12)+1))b|=1;}for(int i=0;i<3;i++){c<<=1;if(READ_BIT(vch,3*(i+24)+1))c|=1;}for(int i=0;i<22;i++){c<<=1;if(READ_BIT(vch,i+81))c|=1;}uint8_t v[7];memset(v,0,7);for(int i=0;i<12;i++){WRITE_BIT(v,i,((a<<(i+20))&0x80000000)!=0);WRITE_BIT(v,i+12,((b<<(i+20))&0x80000000)!=0);}for(int i=0;i<25;i++)WRITE_BIT(v,i+24,((c<<(i+7))&0x80000000)!=0);memcpy(ambe35+7*j,v,7);}return true;}
extern "C" {
JNIEXPORT jlong JNICALL Java_cn_arch_dmr_YsfCodec_nativeCreate(JNIEnv*,jclass){YsfCtx*ctx=new YsfCtx();ctx->blip25=arch_blip25_create_natural();return (jlong)(intptr_t)ctx;}
JNIEXPORT void JNICALL Java_cn_arch_dmr_YsfCodec_nativeDestroy(JNIEnv*,jclass,jlong h){if(!h)return;YsfCtx*ctx=(YsfCtx*)(intptr_t)h;if(ctx->blip25)arch_blip25_destroy(ctx->blip25);delete ctx;}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_YsfCodec_nativeEncodeHeader(JNIEnv*e,jclass,jlong h,jbyteArray out,jbyteArray call,jint txcnt,jboolean eot){if(!h||!out||!call)return -1;YsfCtx*ctx=(YsfCtx*)(intptr_t)h;if(ctx->blip25)arch_blip25_reset(ctx->blip25);if(e->GetArrayLength(out)<155||e->GetArrayLength(call)<10)return -2;jbyte*o=e->GetByteArrayElements(out,0);jbyte*c=e->GetByteArrayElements(call,0);encode_header((uint8_t*)o,(uint8_t*)c,(uint32_t)txcnt,eot==JNI_TRUE);e->ReleaseByteArrayElements(call,c,JNI_ABORT);e->ReleaseByteArrayElements(out,o,0);return JNI_TRUE;}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_YsfCodec_nativeEncodeVoice(JNIEnv*e,jclass,jlong h,jbyteArray out,jbyteArray call,jshortArray pcm,jint txcnt){if(!h||!out||!call||!pcm)return -1;if(e->GetArrayLength(out)<155||e->GetArrayLength(call)<10||e->GetArrayLength(pcm)<800)return -2;jbyte*o=e->GetByteArrayElements(out,0);jbyte*c=e->GetByteArrayElements(call,0);jshort*p=e->GetShortArrayElements(pcm,0);uint8_t a[35];YsfCtx*ctx=(YsfCtx*)(intptr_t)h;bool ok=true;if(ctx->blip25){for(int i=0;i<5;i++){if(arch_blip25_encode_natural(ctx->blip25,(const int16_t*)(p+i*160),a+i*7)!=0){ok=false;break;}}}else{for(int i=0;i<5;i++)ctx->voc.encode_2450((int16_t*)(p+i*160),a+i*7);}if(ok)encode_voice((uint8_t*)o,(uint8_t*)c,a,(uint32_t)txcnt);e->ReleaseShortArrayElements(pcm,p,JNI_ABORT);e->ReleaseByteArrayElements(call,c,JNI_ABORT);e->ReleaseByteArrayElements(out,o,0);return ok?JNI_TRUE:JNI_FALSE;}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_YsfCodec_nativeDecodeFrameType(JNIEnv*e,jclass,jbyteArray in){if(!in||e->GetArrayLength(in)<155)return -1;jbyte*b=e->GetByteArrayElements(in,0);CYSFFICH f;bool ok=f.decode((uint8_t*)b+35);int r=ok?(int)f.getFI():-1;e->ReleaseByteArrayElements(in,b,JNI_ABORT);return r;}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_YsfCodec_nativeDecodeHeader(JNIEnv*e,jclass,jbyteArray in,jbyteArray src,jbyteArray dst){if(!in||!src||!dst)return -2;if(e->GetArrayLength(in)<155||e->GetArrayLength(src)<10||e->GetArrayLength(dst)<10)return -2;jbyte*b=e->GetByteArrayElements(in,0);jbyte*s=e->GetByteArrayElements(src,0);jbyte*d=e->GetByteArrayElements(dst,0);bool ok=decode_header((uint8_t*)b+35,(uint8_t*)s,(uint8_t*)d);e->ReleaseByteArrayElements(dst,d,ok?0:JNI_ABORT);e->ReleaseByteArrayElements(src,s,ok?0:JNI_ABORT);e->ReleaseByteArrayElements(in,b,JNI_ABORT);return ok?JNI_TRUE:JNI_FALSE;}
JNIEXPORT jboolean JNICALL Java_cn_arch_dmr_YsfCodec_nativeEncodeVoiceFromAmbe(JNIEnv*e,jclass,jlong h,jbyteArray out,jbyteArray call,jbyteArray ambe35,jint txcnt){
    if(!h||!out||!call||!ambe35)return JNI_FALSE;
    if(e->GetArrayLength(out)<155||e->GetArrayLength(call)<10||e->GetArrayLength(ambe35)<35)return JNI_FALSE;
    jbyte*o=e->GetByteArrayElements(out,0);jbyte*c=e->GetByteArrayElements(call,0);jbyte*a=e->GetByteArrayElements(ambe35,0);
    encode_voice((uint8_t*)o,(uint8_t*)c,(uint8_t*)a,(uint32_t)txcnt);
    e->ReleaseByteArrayElements(ambe35,a,JNI_ABORT);e->ReleaseByteArrayElements(call,c,JNI_ABORT);e->ReleaseByteArrayElements(out,o,0);
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL Java_cn_arch_dmr_YsfCodec_nativeDecodeVoiceToAmbe(JNIEnv*e,jclass,jlong h,jbyteArray in,jbyteArray ambe35,jbyteArray src,jbyteArray dst){
    if(!h||!in||!ambe35||!src||!dst)return JNI_FALSE;
    if(e->GetArrayLength(in)<155||e->GetArrayLength(ambe35)<35||e->GetArrayLength(src)<10||e->GetArrayLength(dst)<10)return JNI_FALSE;
    jbyte*b=e->GetByteArrayElements(in,0);jbyte*a=e->GetByteArrayElements(ambe35,0);jbyte*s=e->GetByteArrayElements(src,0);jbyte*d=e->GetByteArrayElements(dst,0);
    int fi=0,fn=0;bool ok=decode_voice((uint8_t*)b+35,(uint8_t*)a,(uint8_t*)s,(uint8_t*)d,&fi,&fn);
    e->ReleaseByteArrayElements(dst,d,ok?0:JNI_ABORT);e->ReleaseByteArrayElements(src,s,ok?0:JNI_ABORT);e->ReleaseByteArrayElements(ambe35,a,ok?0:JNI_ABORT);e->ReleaseByteArrayElements(in,b,JNI_ABORT);
    return ok?JNI_TRUE:JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_cn_arch_dmr_YsfCodec_nativeDecodeVoice(JNIEnv*e,jclass,jlong h,jbyteArray in,jshortArray pcm,jbyteArray src,jbyteArray dst){if(!h||!in||!pcm||!src||!dst)return -1;if(e->GetArrayLength(in)<155||e->GetArrayLength(pcm)<800||e->GetArrayLength(src)<10||e->GetArrayLength(dst)<10)return -2;jbyte*b=e->GetByteArrayElements(in,0);jshort*p=e->GetShortArrayElements(pcm,0);jbyte*s=e->GetByteArrayElements(src,0);jbyte*d=e->GetByteArrayElements(dst,0);uint8_t a[35];int fi=0,fn=0;bool ok=decode_voice((uint8_t*)b+35,a,(uint8_t*)s,(uint8_t*)d,&fi,&fn);YsfCtx*ctx=(YsfCtx*)(intptr_t)h;if(ok){if(ctx->blip25){for(int i=0;i<5;i++){if(arch_blip25_decode_natural(ctx->blip25,a+i*7,(int16_t*)(p+i*160))!=0){ok=false;break;}}}else{for(int i=0;i<5;i++)ctx->voc.decode_2450((int16_t*)(p+i*160),a+i*7);}}e->ReleaseByteArrayElements(dst,d,ok?0:JNI_ABORT);e->ReleaseByteArrayElements(src,s,ok?0:JNI_ABORT);e->ReleaseShortArrayElements(pcm,p,ok?0:JNI_ABORT);e->ReleaseByteArrayElements(in,b,JNI_ABORT);return ok?JNI_TRUE:JNI_FALSE;}
}






extern "C" {
JNIEXPORT jlong JNICALL Java_cn_arch_dmr_NxdnCodec_nativeCreate(JNIEnv*,jclass){return (jlong)(intptr_t)arch_blip25_create_natural();}
JNIEXPORT void JNICALL Java_cn_arch_dmr_NxdnCodec_nativeDestroy(JNIEnv*,jclass,jlong h){if(h)arch_blip25_destroy((void*)(intptr_t)h);}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_NxdnCodec_nativeReset(JNIEnv*,jclass,jlong h){return h?arch_blip25_reset((void*)(intptr_t)h):-1;}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_NxdnCodec_nativeEncode(JNIEnv*e,jclass,jlong h,jshortArray pcm,jbyteArray out){
 if(!h||!pcm||!out)return -1; if(e->GetArrayLength(pcm)!=160||e->GetArrayLength(out)!=7)return -2;
 jshort*p=e->GetShortArrayElements(pcm,0); jbyte*o=e->GetByteArrayElements(out,0); if(!p||!o){if(p)e->ReleaseShortArrayElements(pcm,p,JNI_ABORT);if(o)e->ReleaseByteArrayElements(out,o,JNI_ABORT);return -3;}
 int rc=arch_blip25_encode_natural((void*)(intptr_t)h,(const int16_t*)p,(uint8_t*)o);
 e->ReleaseShortArrayElements(pcm,p,JNI_ABORT); e->ReleaseByteArrayElements(out,o,rc==0?0:JNI_ABORT); return rc;
}
JNIEXPORT jint JNICALL Java_cn_arch_dmr_NxdnCodec_nativeDecode(JNIEnv*e,jclass,jlong h,jbyteArray in,jshortArray pcm){
 if(!h||!in||!pcm)return -1; if(e->GetArrayLength(in)!=7||e->GetArrayLength(pcm)!=160)return -2;
 jbyte*i=e->GetByteArrayElements(in,0); jshort*p=e->GetShortArrayElements(pcm,0); if(!i||!p){if(i)e->ReleaseByteArrayElements(in,i,JNI_ABORT);if(p)e->ReleaseShortArrayElements(pcm,p,JNI_ABORT);return -3;}
 int rc=arch_blip25_decode_natural((void*)(intptr_t)h,(const uint8_t*)i,(int16_t*)p);
 e->ReleaseByteArrayElements(in,i,JNI_ABORT); e->ReleaseShortArrayElements(pcm,p,rc==0?0:JNI_ABORT); return rc;
}
}
