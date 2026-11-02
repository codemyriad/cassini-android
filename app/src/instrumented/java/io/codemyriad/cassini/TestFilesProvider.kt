package io.codemyriad.cassini

import androidx.core.content.FileProvider

/**
 * Instrumented builds only (debug and releaseTest): content uris for the device tests that hand a file to the app
 * through "Open with" or a share, as another app's provider would. MainActivity takes only another app's
 * `content://` uri, so the tests need a provider whose authority is not one of Cassini's own
 * (`DeepLinks.external`). Not exported; it serves the app's cache directory, where the tests copy their clips.
 */
class TestFilesProvider : FileProvider()
