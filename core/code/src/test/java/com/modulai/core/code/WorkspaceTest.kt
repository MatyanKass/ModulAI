package com.modulai.core.code

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal fun refused(expected: Refusal, block: () -> Unit) {
    try {
        block()
        fail("Expected $expected")
    } catch (e: WorkspaceException) {
        assertEquals(e.message, expected, e.refusal)
    }
}

internal fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArrayInputStream {
    val bytes = ByteArrayOutputStream()
    ZipOutputStream(bytes).use { zip ->
        for ((name, data) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(data)
            zip.closeEntry()
        }
    }
    return ByteArrayInputStream(bytes.toByteArray())
}

class WorkspaceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun workspace(limits: Limits = Limits()) = Workspace(File(tmp.root, "projects"), limits)

    @Test fun projectsAreCreatedListedRenamedAndDeleted() {
        val ws = workspace()
        val a = ws.create("  Site  ")
        val b = ws.create("site")
        assertEquals("Site", a.name)
        assertEquals("site 2", b.name)
        assertEquals(setOf(a.id, b.id), ws.projects().map { it.id }.toSet())

        assertEquals("Shop", ws.rename(a.id, "Shop").name)
        assertEquals(a.id, ws.findByName("shop")?.id)
        refused(Refusal.NAME_TAKEN) { ws.rename(a.id, "SITE 2") }
        refused(Refusal.INVALID_NAME) { ws.create("   ") }

        ws.delete(a.id)
        assertNull(ws.find(a.id))
        refused(Refusal.NOT_FOUND) { ws.open(a.id) }
        refused(Refusal.NOT_FOUND) { ws.open("../${b.id}") }
    }

    @Test fun filesAreWrittenReadListedMovedAndDeleted() {
        val dir = workspace().let { it.open(it.create("p").id) }
        dir.write("src/app.js", "let a = 1\n")
        dir.write("./index.html", "<html>")
        dir.write("src\\util.js", "x")
        assertEquals(listOf("index.html", "src/app.js", "src/util.js"), dir.files().map { it.path })
        assertEquals("let a = 1\n", dir.read("src/app.js"))
        assertEquals("src/app.js", dir.normalize("./src//app.js"))

        dir.move("src/util.js", "lib/util.js")
        assertTrue(dir.exists("lib/util.js"))
        refused(Refusal.ALREADY_EXISTS) { dir.move("index.html", "src/app.js") }

        dir.delete("lib/util.js")
        assertFalse("an emptied folder goes away", File(dir.dir, "lib").exists())
        refused(Refusal.NOT_FOUND) { dir.read("lib/util.js") }
        refused(Refusal.IS_DIRECTORY) { dir.read("src") }
        refused(Refusal.IS_DIRECTORY) { dir.write("src", "x") }
        refused(Refusal.INVALID_PATH) { dir.write("index.html/nested.txt", "x") }
    }

    @Test fun pathsCannotEscapeTheProject() {
        val ws = workspace()
        val dir = ws.open(ws.create("p").id)
        val secret = tmp.newFile("secret.txt").apply { writeText("secret") }

        val escapes = listOf(
            "../secret.txt", "..", "a/../../secret.txt", "src/../../../etc/passwd", "..\\secret.txt",
            "/etc/passwd", secret.absolutePath, "C:\\Windows\\win.ini", "~/secret",
        )
        for (path in escapes) {
            refused(Refusal.OUTSIDE_PROJECT) { dir.resolve(path) }
            refused(Refusal.OUTSIDE_PROJECT) { dir.write(path, "x") }
        }
        for (path in listOf("", "   ", ".", "./", "a\u0000b")) refused(Refusal.INVALID_PATH) { dir.resolve(path) }
        refused(Refusal.INVALID_PATH) { dir.resolve("a/".repeat(40) + "f") }
        assertEquals("secret", secret.readText())
        // The app's own files about the project sit next to its files and stay out of reach too.
        refused(Refusal.OUTSIDE_PROJECT) { dir.read("../project.json") }
    }

    @Test fun symlinksLeadingOutsideAreRejected() {
        val ws = workspace()
        val dir = ws.open(ws.create("p").id)
        val outside = tmp.newFolder("outside")
        File(outside, "secret.txt").writeText("secret")
        Files.createSymbolicLink(File(dir.dir, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(dir.dir, "file-link").toPath(), File(outside, "secret.txt").toPath())

        refused(Refusal.OUTSIDE_PROJECT) { dir.read("link/secret.txt") }
        refused(Refusal.OUTSIDE_PROJECT) { dir.write("link/new.txt", "x") }
        refused(Refusal.OUTSIDE_PROJECT) { dir.read("file-link") }
        refused(Refusal.OUTSIDE_PROJECT) { dir.write("file-link", "overwritten") }
        refused(Refusal.OUTSIDE_PROJECT) { dir.delete("file-link") }
        assertEquals("secret", File(outside, "secret.txt").readText())
        assertFalse(File(outside, "new.txt").exists())
        assertTrue("links are not listed", dir.files().isEmpty())
    }

    @Test fun limitsAreEnforced() {
        val ws = workspace(Limits(maxFileBytes = 100, maxProjectBytes = 250, maxFiles = 3))
        val dir = ws.open(ws.create("p").id)
        refused(Refusal.FILE_TOO_LARGE) { dir.write("big.txt", "x".repeat(101)) }
        assertFalse(dir.exists("big.txt"))
        assertTrue("nothing is left behind by a refused write", dir.files().isEmpty())

        dir.write("a.txt", "x".repeat(100))
        dir.write("b.txt", "x".repeat(100))
        refused(Refusal.PROJECT_FULL) { dir.write("c.txt", "x".repeat(60)) }
        dir.write("a.txt", "y".repeat(100))
        dir.write("c.txt", "x".repeat(50))
        refused(Refusal.PROJECT_FULL) { dir.write("c.txt", "z".repeat(51)) }
        assertEquals("a refused overwrite keeps the old content", "x".repeat(50), dir.read("c.txt"))

        refused(Refusal.TOO_MANY_FILES) { dir.write("d.txt", "1") }
        dir.write("c.txt", "2")
    }

    @Test fun binaryFilesAreNotReadAsText() {
        val dir = workspace().let { it.open(it.create("p").id) }
        dir.writeBytes("logo.png", byteArrayOf(1, 0, 2))
        refused(Refusal.BINARY) { dir.read("logo.png") }
        assertArrayEquals(byteArrayOf(1, 0, 2), dir.readBytes("logo.png"))
    }

    @Test fun exportAndImportRoundTrip() {
        val ws = workspace()
        val dir = ws.open(ws.create("p").id)
        dir.write("index.html", "<html>")
        dir.write("js/app.js", "run()")
        dir.writeBytes("img/a.bin", byteArrayOf(0, 1, 2))

        val bytes = ByteArrayOutputStream().also { dir.exportZip(it) }.toByteArray()
        val names = generateSequence(ZipInputStream(bytes.inputStream())) { it }.map { it.nextEntry?.name }
            .takeWhile { it != null }.toList()
        assertEquals(listOf("img/a.bin", "index.html", "js/app.js"), names)

        val copy = ws.open(ws.importZip("copy", bytes.inputStream()).id)
        assertEquals(dir.files(), copy.files())
        assertEquals("run()", copy.read("js/app.js"))
    }

    @Test fun importStripsTheWrappingFolder() {
        val ws = workspace()
        val project = ws.importZip("site", zipOf("site-main/" to ByteArray(0), "site-main/index.html" to "a".toByteArray(), "site-main/css/s.css" to "b".toByteArray()))
        assertEquals(listOf("css/s.css", "index.html"), ws.open(project.id).files().map { it.path })
    }

    @Test fun importRejectsZipSlip() {
        val ws = workspace()
        for (name in listOf("../evil.txt", "a/../../evil.txt", "/tmp/evil.txt", "..\\evil.txt")) {
            refused(Refusal.OUTSIDE_PROJECT) { ws.importZip("bad", zipOf("ok.txt" to "1".toByteArray(), name to "evil".toByteArray())) }
        }
        assertTrue("a refused archive leaves no project", ws.projects().isEmpty())
        assertFalse(File(tmp.root, "evil.txt").exists())
        assertFalse(File(tmp.root, "projects/evil.txt").exists())
    }

    @Test fun importEnforcesLimits() {
        val ws = workspace(Limits(maxFileBytes = 1000, maxProjectBytes = 1500, maxFiles = 3))
        // Highly compressible: the archive is tiny, the content is not.
        refused(Refusal.FILE_TOO_LARGE) { ws.importZip("bomb", zipOf("a.txt" to ByteArray(100_000))) }
        refused(Refusal.PROJECT_FULL) { ws.importZip("big", zipOf("a" to ByteArray(900), "b" to ByteArray(900))) }
        refused(Refusal.TOO_MANY_FILES) { ws.importZip("many", zipOf("a" to ByteArray(1), "b" to ByteArray(1), "c" to ByteArray(1), "d" to ByteArray(1))) }
        refused(Refusal.BAD_ARCHIVE) { ws.importZip("junk", "not a zip".byteInputStream()) }
        assertTrue(ws.projects().isEmpty())
    }
}
