package org.cassini.engine.job

import org.cassini.core.Failure
import org.cassini.core.models.ModelPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobFailureTest {
    private val codes = Failure.entries.map { JobFailure.Processing(it).code } +
        DownloadError.entries.map { JobFailure.Download(it, ModelPart.SPEECH, 0).code } +
        JobFailure.CheckpointMismatch.code

    @Test fun noTwoFailuresShareAStoredCode() {
        assertEquals(codes.size, codes.toSet().size)
    }

    @Test fun anInstallFailureOfTheDownloadIsNotTheCoreInstallFailure() {
        val download = JobFailure.Download(DownloadError.INSTALL, ModelPart.SPEECH, 0).code
        assertEquals("DOWNLOAD_INSTALL", download)
        assertEquals(DownloadError.INSTALL, JobFailure.downloadError(download))
        assertNull(JobFailure.downloadError(JobFailure.Processing(Failure.INSTALL).code))
    }

    @Test fun everyDownloadCodeReadsBack() {
        DownloadError.entries.forEach { error ->
            assertEquals(error, JobFailure.downloadError(JobFailure.Download(error, ModelPart.VOICES, 1).code))
        }
        assertNull(JobFailure.downloadError(JobFailure.CheckpointMismatch.code))
    }
}
