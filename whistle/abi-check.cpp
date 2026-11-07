// SPDX-License-Identifier: GPL-3.0-or-later
// Compile the published header against the C ABI consumed by the desktop and future Android worker.
#include <type_traits>
#include "needle.h"

static_assert(std::is_same_v<decltype(&needle_load), int (*)(const unsigned char*, unsigned long long)>);
static_assert(std::is_same_v<decltype(&needle_models), int (*)()>);
static_assert(std::is_same_v<decltype(&needle_last_error), const char* (*)()>);
static_assert(std::is_same_v<decltype(&needle_transcribe),
    int (*)(const float*, int, const char*, const char*, int, char*, int)>);
