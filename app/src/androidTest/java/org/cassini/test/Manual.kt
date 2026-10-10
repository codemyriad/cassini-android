package org.cassini.test

/**
 * Run by hand only: the test needs local inputs or a person, so no scripted selection runs it. Select it with
 * `scripts/device-smoke.sh --annotation Manual` (docs/architecture.md section 9). No test has it yet, so that
 * selection fails with "The selection has no tests." until a test is marked.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Manual
