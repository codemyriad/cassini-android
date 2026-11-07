// SPDX-License-Identifier: GPL-3.0-or-later
// Standalone lab probe, not linked into the app. The future worker must apply the same pre-dlopen guard.
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char** argv) {
    const char* names[] = {"NEEDLE_TELEMETRY", "DO_NOT_TRACK", "ORT_DISABLE_TELEMETRY"};
    const char* values[] = {"0", "1", "1"};
    for (int i = 0; i < 3; ++i) {
        const char* value = getenv(names[i]);
        if (!value || strcmp(value, values[i])) {
            fprintf(stderr, "Privacy guard: %s must be %s before loading\n", names[i], values[i]);
            return 2;
        }
    }
    if (argc != 2) {
        fprintf(stderr, "Usage: load-smoke /absolute/path/libcassini_needle.so\n");
        return 2;
    }
    void* library = dlopen(argv[1], RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fprintf(stderr, "dlopen: %s\n", dlerror());
        return 3;
    }
    const char* symbols[] = {"needle_load", "needle_models", "needle_last_error", "needle_transcribe"};
    for (int i = 0; i < 4; ++i) {
        if (!dlsym(library, symbols[i])) {
            fprintf(stderr, "Missing C API: %s\n", symbols[i]);
            dlclose(library);
            return 4;
        }
    }
    int (*models)(void) = (int (*)(void))dlsym(library, "needle_models");
    if (models() != 0) {
        fprintf(stderr, "Unexpected model loaded before model initialization\n");
        dlclose(library);
        return 5;
    }
    printf("Whistle C API loaded; models=0; pageSize=%ld\n", sysconf(_SC_PAGESIZE));
    return dlclose(library) == 0 ? 0 : 6;
}
