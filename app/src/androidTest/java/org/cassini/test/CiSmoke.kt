package org.cassini.test

/**
 * Needs no models and no microphone: the CI emulator matrix runs it (scripts/ci-emulator-smoke.sh). Select it with
 * `scripts/device-smoke.sh --annotation CiSmoke` (docs/architecture.md section 9).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class CiSmoke
