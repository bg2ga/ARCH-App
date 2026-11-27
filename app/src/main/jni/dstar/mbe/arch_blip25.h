/* ARCH: blip25 (AMBE+2 半速率) C 接口
 * 上游 blip25-vocoder (MIT) — https://github.com/openBLIP25/blip25-vocoder
 * 由 /srv/recordings/build/arch_blip25 编成安卓静态库。
 *
 * 9 字节接口用于 P25 Phase 2 在线帧 (3600x2450)。
 * 7 字节 natural/AMBE_d 接口用于 YSF DN / mbelib 兼容的 2450x2450 净荷。
 */
#ifndef ARCH_BLIP25_H
#define ARCH_BLIP25_H
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
void *arch_blip25_create(void);
void  arch_blip25_destroy(void *v);
int   arch_blip25_reset(void *v);
int   arch_blip25_encode(void *v, const int16_t *pcm, uint8_t *out9);
int   arch_blip25_decode(void *v, const uint8_t *in9, int16_t *pcm);

void *arch_blip25_create_natural(void);
int   arch_blip25_encode_natural(void *v, const int16_t *pcm, uint8_t *out7);
int   arch_blip25_decode_natural(void *v, const uint8_t *in7, int16_t *pcm);
#ifdef __cplusplus
}
#endif
#endif
