// SPDX-License-Identifier: GPL-3.0-or-later
// Execute the actual instruction in an owned guest process before any force-on experiment.
#include <arm_neon.h>
#include <stdio.h>
int main(void) {
    int8x16_t a = vdupq_n_s8(2), b = vdupq_n_s8(3);
    int32x4_t result = vdupq_n_s32(0);
    __asm__ volatile(".arch_extension dotprod\nsdot %0.4s, %1.16b, %2.16b" : "+w"(result) : "w"(a), "w"(b));
    int32_t lanes[4];
    vst1q_s32(lanes, result);
    for (int i = 0; i < 4; i++) if (lanes[i] != 24) return 1;
    puts("SDOT executed: four exact lanes of 24");
    return 0;
}
