package org.cassini.test

/**
 * Needs no models and no microphone. Reserved for the CI emulator matrix (docs/architecture.md section 9): until P11,
 * scripts/ci-emulator-smoke.sh selects the 0.1.0 tests by class and method, not by this annotation, so a test marked
 * with it does not run in CI yet. Select it with `scripts/device-smoke.sh --annotation CiSmoke`.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class CiSmoke
