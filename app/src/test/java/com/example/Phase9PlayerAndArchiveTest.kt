package com.example

import com.example.data.engine.ArchiveFormat
import com.example.data.engine.ArchiveHelper
import com.example.data.engine.PathSecuritySanitizer
import com.example.ui.player.TurboPlayerActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class Phase9PlayerAndArchiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testIsArchiveDetection() {
        assertTrue(ArchiveHelper.isArchive("download.zip"))
        assertTrue(ArchiveHelper.isArchive("archive.RAR"))
        assertTrue(ArchiveHelper.isArchive("backup.7z"))
        assertTrue(ArchiveHelper.isArchive("source.tar.gz"))
        assertTrue(ArchiveHelper.isArchive("source.tgz"))
        assertTrue(ArchiveHelper.isArchive("data.tar"))

        assertFalse(ArchiveHelper.isArchive("video.mp4"))
        assertFalse(ArchiveHelper.isArchive("song.mp3"))
        assertFalse(ArchiveHelper.isArchive("app.apk"))
        assertFalse(ArchiveHelper.isArchive("doc.pdf"))
    }

    @Test
    fun testDetectArchiveFormat() {
        assertEquals(ArchiveFormat.ZIP, ArchiveHelper.detectFormat(File("archive.zip")))
        assertEquals(ArchiveFormat.RAR, ArchiveHelper.detectFormat(File("archive.rar")))
        assertEquals(ArchiveFormat.SEVEN_Z, ArchiveHelper.detectFormat(File("archive.7z")))
        assertEquals(ArchiveFormat.TAR, ArchiveHelper.detectFormat(File("archive.tar")))
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveHelper.detectFormat(File("archive.tar.gz")))
        assertEquals(ArchiveFormat.TAR_GZ, ArchiveHelper.detectFormat(File("archive.tgz")))
        assertEquals(ArchiveFormat.UNKNOWN, ArchiveHelper.detectFormat(File("archive.bin")))
    }

    @Test
    fun testDefaultOutputDirectorySanitization() {
        val root = tempFolder.newFolder("downloads")
        val zipFile = File(root, "project_v1.zip")
        val targetDir = ArchiveHelper.getDefaultOutputDirectory(zipFile)

        assertEquals("project_v1", targetDir.name)
        assertEquals(root.absolutePath, targetDir.parentFile?.absolutePath)
    }

    @Test
    fun testZipArchiveExtraction() = runBlocking {
        val root = tempFolder.newFolder("zip_test")
        val zipFile = File(root, "sample.zip")
        val destDir = File(root, "sample_extracted")

        // Create a test ZIP file with 2 files and a subfolder
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("hello.txt"))
            zos.write("Hello Zireael!".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("subfolder/world.txt"))
            zos.write("World of high performance downloads".toByteArray())
            zos.closeEntry()
        }

        val result = ArchiveHelper.extractArchive(zipFile, destDir).getOrThrow()

        assertTrue(result.success)
        assertEquals(2, result.extractedCount)
        assertTrue(result.totalBytesExtracted > 0)

        val file1 = File(destDir, "hello.txt")
        val file2 = File(destDir, "subfolder/world.txt")

        assertTrue("file1 must exist", file1.exists())
        assertTrue("file2 must exist", file2.exists())
        assertEquals("Hello Zireael!", file1.readText())
        assertEquals("World of high performance downloads", file2.readText())
    }

    @Test
    fun testZipSlipMaliciousEntryBlocked() = runBlocking {
        val root = tempFolder.newFolder("zip_slip_test")
        val zipFile = File(root, "malicious.zip")
        val destDir = File(root, "safe_dir")
        destDir.mkdirs()

        // Create a ZIP containing a Zip Slip path traversal attempt
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("valid.txt"))
            zos.write("Valid file content".toByteArray())
            zos.closeEntry()

            // Dangerous entry escaping to parent
            zos.putNextEntry(ZipEntry("../../escaped_hacked.txt"))
            zos.write("Hacked file outside safe bounds!".toByteArray())
            zos.closeEntry()
        }

        val result = ArchiveHelper.extractArchive(zipFile, destDir).getOrThrow()

        // Valid file should be extracted safely inside destDir
        val validFile = File(destDir, "valid.txt")
        assertTrue("Valid file should be extracted", validFile.exists())

        // Escaped file MUST NOT exist in root or outside destDir
        val leakedFile = File(root, "escaped_hacked.txt")
        assertFalse("Zip Slip traversal MUST NOT escape destination directory", leakedFile.exists())
    }

    @Test
    fun testTurboPlayerActivityExtrasContract() {
        assertEquals("extra_file_path", TurboPlayerActivity.EXTRA_FILE_PATH)
        assertEquals("extra_media_title", TurboPlayerActivity.EXTRA_MEDIA_TITLE)
        assertEquals("extra_mime_type", TurboPlayerActivity.EXTRA_MIME_TYPE)
        assertEquals("extra_is_vault", TurboPlayerActivity.EXTRA_IS_VAULT)
    }
}
