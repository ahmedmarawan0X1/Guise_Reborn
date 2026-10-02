package com.houvven.guise.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    @Test
    fun explicitPrereleaseChannelWorksWithoutVersionSuffix() {
        assertEquals(
            listOf("latest-prerelease.json", "latest-release.json"),
            updateManifestNames("2.1.2", prerelease = true),
        )
        assertEquals(
            listOf("latest-release.json"),
            updateManifestNames("2.1.2", prerelease = false),
        )
    }

    @Test
    fun prereleaseBuildChecksBothChannels() {
        assertEquals(
            listOf("latest-prerelease.json", "latest-release.json"),
            updateManifestNames("2.0.0-beta.4"),
        )
    }

    @Test
    fun releaseBuildChecksOnlyReleaseChannel() {
        assertEquals(
            listOf("latest-release.json"),
            updateManifestNames("2.0.0"),
        )
    }

    @Test
    fun releaseCandidateIsTreatedAsPrerelease() {
        assertEquals(
            listOf("latest-prerelease.json", "latest-release.json"),
            updateManifestNames("2.0.0-rc.1"),
        )
    }

    @Test
    fun stableChannelRejectsPrereleaseManifest() {
        assertThrows(IllegalStateException::class.java) {
            validateUpdateChannel(actualPrerelease = true, expectedPrerelease = false)
        }
    }

    @Test
    fun matchingManifestChannelIsAccepted() {
        validateUpdateChannel(actualPrerelease = false, expectedPrerelease = false)
        validateUpdateChannel(actualPrerelease = true, expectedPrerelease = true)
    }

    @Test
    fun readyDownloadMustBelongToDetectedVersionAndArtifact() {
        val sha = "76b1c1056e149f29547d6bc242d43ec8c8e0c01005e9b43462953f9019d8161c"
        assertTrue(readyArtifactMatches(21, sha, 21, sha.uppercase()))
        assertFalse(readyArtifactMatches(21, sha, 19, sha))
        assertFalse(readyArtifactMatches(21, sha, 21, "old-build"))
        assertFalse(readyArtifactMatches(21, sha, -1, null))
    }
}
