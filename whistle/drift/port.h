// SPDX-License-Identifier: GPL-3.0-or-later
// Declaration-only compatibility for unmodified musl math sources against NDK headers.
#ifndef hidden
#define hidden __attribute__((visibility("hidden")))
#endif
#ifndef weak_alias
#define weak_alias(old, new)
#endif
#ifndef __BYTE_ORDER
#define __BYTE_ORDER __BYTE_ORDER__
#endif
#ifndef __LITTLE_ENDIAN
#define __LITTLE_ENDIAN __ORDER_LITTLE_ENDIAN__
#endif
#ifndef __BIG_ENDIAN
#define __BIG_ENDIAN __ORDER_BIG_ENDIAN__
#endif
