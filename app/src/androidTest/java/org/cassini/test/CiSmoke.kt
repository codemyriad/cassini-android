package org.cassini.test

/**
 * Needs no models and no microphone: the selection of the CI emulator matrix (docs/architecture.md section 9), which
 * scripts/ci-emulator-smoke.sh runs with `scripts/device-smoke.sh --annotation CiSmoke`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class CiSmoke
