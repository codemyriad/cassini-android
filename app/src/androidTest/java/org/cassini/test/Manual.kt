package org.cassini.test

/**
 * Run by hand only: the test needs local inputs, a person or its own script, so no scripted selection runs it
 * (docs/architecture.md section 9). `NoteOpeningTimingTest` has it; scripts/note-opening-timing.sh runs it with the
 * arguments it needs. Without them it is skipped, so `scripts/device-smoke.sh --annotation Manual` fails.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Manual
