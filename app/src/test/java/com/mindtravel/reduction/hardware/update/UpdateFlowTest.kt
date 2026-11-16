package com.mindtravel.reduction.hardware.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Runs the download-and-patch half of an update with the app's own code against a real
 * manifest. Skipped unless both are set:
 *
 *   REDUCTION_UPDATE_MANIFEST   URL of manifest.json
 *   REDUCTION_INSTALLED_APK     path of the APK the phone currently has
 */
class UpdateFlowTest {

    @Test
    fun patchFromTheInstalledApkYieldsThePublishedApk() {
        val manifestUrl = System.getenv("REDUCTION_UPDATE_MANIFEST").orEmpty()
        val installed = File(System.getenv("REDUCTION_INSTALLED_APK").orEmpty())
        assumeTrue(manifestUrl.isNotBlank() && installed.isFile)

        val client = UpdateClient()
        val manifest = client.fetch(manifestUrl)
        val patch = requireNotNull(manifest.patch) { "manifest has no patch" }
        val dir = Files.createTempDirectory("reduction-update").toFile()
        try {
            val patchFile = client.downloadFile(manifestUrl, patch.apk, File(dir, "patch.bin"), minBytes = 1_000)
            assertEquals(patch.sha256, UpdateInstaller.sha256(patchFile))

            val patched = File(dir, "patched.apk")
            BsPatch.apply(installed, patchFile, patched)
            assertEquals(manifest.sha256, UpdateInstaller.sha256(patched))

            val full = client.download(manifestUrl, manifest, File(dir, "full.apk"))
            assertEquals(manifest.sha256, UpdateInstaller.sha256(full))
            assertTrue(manifest.versionCode > 0 && manifest.versionName.isNotBlank())
        } finally {
            dir.deleteRecursively()
        }
    }
}
