package org.cassini.test

/**
 * Run by hand only: the test needs local inputs, a person or its own script, so no scripted selection runs it
 * (docs/architecture.md section 9). `NoteOpeningTimingTest` (scripts/note-opening-timing.sh gives its arguments),
 * `ModelDownloadFlowTest` (`--arg emptyModelRoot=true`), `AcceptanceWalkTest` (scripts/acceptance-walk.sh gives its
 * arguments) and the `DeviceSmokeTest` tests that read the local YouTube clip have it. Without their inputs they are
 * skipped, so `scripts/device-smoke.sh --annotation Manual` fails.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class Manual
