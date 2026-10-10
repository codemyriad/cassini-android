package org.cassini.test

/**
 * Needs the installed speech, speaker and voice models (emulator-5554). Only with `-e downloadModels true`, which
 * scripts/device-smoke.sh gives, does DeviceSmokeTest download a missing speech model first. Select it with
 * `scripts/device-smoke.sh --annotation NeedsModels` (docs/architecture.md section 9).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class NeedsModels
