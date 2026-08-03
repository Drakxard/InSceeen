package com.inscreen.mic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubUpdateClientTest {
    @Test fun detectsOnlyStrictlyNewerVersions() {
        assertTrue(GitHubUpdateClient.isNewer("1.6.0", "1.5.0"))
        assertTrue(GitHubUpdateClient.isNewer("1.10.0", "1.9.9"))
        assertFalse(GitHubUpdateClient.isNewer("1.6.0", "1.6.0"))
        assertFalse(GitHubUpdateClient.isNewer("1.5.9", "1.6.0"))
    }

    @Test fun readsTheSignedApkFromLatestReleasePayload() {
        val release = GitHubUpdateClient.parseRelease(
            """{
                "tag_name":"v1.7.0",
                "body":"Mejoras del widget",
                "assets":[{
                    "name":"InScreenMic.apk",
                    "browser_download_url":"https://github.com/Drakxard/InSceeen/releases/download/v1.7.0/InScreenMic.apk"
                }]
            }""".trimIndent()
        )

        assertEquals("v1.7.0", release.tag)
        assertEquals("1.7.0", release.version)
        assertEquals("Mejoras del widget", release.notes)
    }
}
