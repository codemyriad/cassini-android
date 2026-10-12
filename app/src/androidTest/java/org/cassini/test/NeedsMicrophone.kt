package org.cassini.test

/**
 * Needs a microphone. On an emulator the audio may be silence, so these tests check files and states, not words.
 * Select it with `scripts/device-smoke.sh --annotation NeedsMicrophone` (docs/architecture.md section 9).
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class NeedsMicrophone
