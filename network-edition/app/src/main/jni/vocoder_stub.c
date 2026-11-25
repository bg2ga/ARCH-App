#include <stdint.h>
#include <stddef.h>
void *arch_blip25_create(void){ return 0; }
void  arch_blip25_destroy(void *v){ (void)v; }
int   arch_blip25_reset(void *v){ (void)v; return -1; }
