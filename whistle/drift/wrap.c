// SPDX-License-Identifier: GPL-3.0-or-later
// Lab-only link interposition; never enabled in production. All counts are process-local.
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <sys/auxv.h>

#ifndef DISPATCH
#define DISPATCH -1
#endif
#ifndef PINNED_MATH
#define PINNED_MATH 0
#endif

static _Atomic unsigned long aux_calls;
static _Atomic unsigned long math_calls[12];
unsigned long __real_getauxval(unsigned long);
unsigned long __wrap_getauxval(unsigned long type) {
    unsigned long value = __real_getauxval(type);
    atomic_fetch_add_explicit(&aux_calls, 1, memory_order_relaxed);
    // Only the SDOT bit read by the verified Needle constructor changes. LSE and other bits stay real.
    if (type == AT_HWCAP && DISPATCH == 0) value &= ~(1UL << 20);
    if (type == AT_HWCAP && DISPATCH == 1) value |= 1UL << 20;
    return value;
}

#if PINNED_MATH
#define IMPL(name) cassini_musl_##name
#else
#define IMPL(name) __real_##name
#endif
#define UNARY(type, name, index) \
    type IMPL(name)(type); \
    type __wrap_##name(type x) { \
        atomic_fetch_add_explicit(&math_calls[index], 1, memory_order_relaxed); \
        return IMPL(name)(x); \
    }
UNARY(float, expf, 0)
UNARY(float, logf, 1)
UNARY(float, sinf, 2)
UNARY(float, cosf, 3)
UNARY(double, exp, 6)
UNARY(double, log, 7)
UNARY(double, log10, 8)
UNARY(double, sin, 9)
UNARY(double, cos, 10)
float IMPL(powf)(float, float);
float __wrap_powf(float x, float y) {
    atomic_fetch_add_explicit(&math_calls[4], 1, memory_order_relaxed);
    return IMPL(powf)(x, y);
}
void IMPL(sincosf)(float, float *, float *);
void __wrap_sincosf(float x, float *s, float *c) {
    atomic_fetch_add_explicit(&math_calls[5], 1, memory_order_relaxed);
    IMPL(sincosf)(x, s, c);
}
void IMPL(sincos)(double, double *, double *);
void __wrap_sincos(double x, double *s, double *c) {
    atomic_fetch_add_explicit(&math_calls[11], 1, memory_order_relaxed);
    IMPL(sincos)(x, s, c);
}

__attribute__((visibility("default"))) const char *cassini_whistle_probe(void) {
    static char json[2048];
    unsigned long hw = __real_getauxval(AT_HWCAP), hw2 = __real_getauxval(AT_HWCAP2);
    uint64_t fpcr;
    __asm__ volatile("mrs %0, fpcr" : "=r"(fpcr));
    snprintf(json, sizeof(json),
        "{\"realHwcap\":\"0x%lx\",\"realHwcap2\":\"0x%lx\",\"dispatch\":%d,"
        "\"effectiveDotprod\":%s,\"pinnedMath\":%s,\"getauxvalCalls\":%lu,\"fpcr\":\"0x%llx\","
        "\"mathCalls\":{\"expf\":%lu,\"logf\":%lu,\"sinf\":%lu,\"cosf\":%lu,\"powf\":%lu,\"sincosf\":%lu,"
        "\"exp\":%lu,\"log\":%lu,\"log10\":%lu,\"sin\":%lu,\"cos\":%lu,\"sincos\":%lu}}",
        hw, hw2, DISPATCH, (DISPATCH == 1 || (DISPATCH == -1 && (hw & (1UL << 20)))) ? "true" : "false",
        PINNED_MATH ? "true" : "false", atomic_load(&aux_calls), (unsigned long long)fpcr,
        atomic_load(&math_calls[0]), atomic_load(&math_calls[1]), atomic_load(&math_calls[2]),
        atomic_load(&math_calls[3]), atomic_load(&math_calls[4]), atomic_load(&math_calls[5]),
        atomic_load(&math_calls[6]), atomic_load(&math_calls[7]), atomic_load(&math_calls[8]),
        atomic_load(&math_calls[9]), atomic_load(&math_calls[10]), atomic_load(&math_calls[11]));
    return json;
}
