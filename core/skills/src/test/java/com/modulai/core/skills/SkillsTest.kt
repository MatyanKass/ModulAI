package com.modulai.core.skills

import com.modulai.core.agent.AgentLog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

private fun md(name: String, description: String = "Does $name", body: String = "Do the thing.") =
    "---\nname: $name\ndescription: $description\n---\n$body\n"

class SkillParserTest {
    @Test fun readsFrontMatterAndBody() {
        val skill = SkillParser.parse(
            "---\nname: image-prompt\ndescription: \"Write a prompt: detailed\"\ntools: [web_search, 'fetch_page']\nlanguage: en\n---\n\n# Title\n\nBody text.\n",
            "folder",
        )
        assertEquals("image-prompt", skill.name)
        assertEquals("Write a prompt: detailed", skill.description)
        assertEquals(listOf("web_search", "fetch_page"), skill.tools)
        assertEquals("en", skill.language)
        assertEquals("# Title\n\nBody text.", skill.instructions)
    }

    @Test fun readsDashListsCommentsAndCrlf() {
        val text = "---\r\n# a comment\r\nName: Review   # trailing\r\ntools:\r\n  - research\r\n  - \"web_search\"\r\ndescription: 'It''s short'\r\n---\r\nBody\r\nline two\r\n"
        val skill = SkillParser.parse(text, "x")
        assertEquals("Review", skill.name)
        assertEquals(listOf("research", "web_search"), skill.tools)
        assertEquals("It's short", skill.description)
        assertEquals("Body\nline two", skill.instructions)
    }

    @Test fun readsUnindentedDashListsBlockScalarsAndCommaSeparatedTools() {
        val skill = SkillParser.parse(
            "---\ndescription: >\n  Folded text\n  on two lines\ntools:\n- a\n- b\nunknown: {x: 1}\n---\nBody",
            "x",
        )
        assertEquals("Folded text on two lines", skill.description)
        assertEquals(listOf("a", "b"), skill.tools)
        assertEquals(listOf("a", "b"), SkillParser.parse("---\ntools: a, b\n---\nBody", "x").tools)
    }

    @Test fun missingFrontMatterFallsBackToFolderNameAndFirstLine() {
        val skill = SkillParser.parse("# Heading\n\nFirst paragraph here.\nMore.", "my-folder")
        assertEquals("my-folder", skill.name)
        assertEquals("First paragraph here.", skill.description)
        assertTrue(skill.tools.isEmpty())
        assertNull(skill.language)
        assertTrue(skill.instructions.startsWith("# Heading"))
    }

    @Test fun unclosedFrontMatterIsTreatedAsBody() {
        val skill = SkillParser.parse("---\nname: x\nNo closing line", "fallback")
        assertEquals("fallback", skill.name)
        assertTrue(skill.instructions.contains("No closing line"))
    }

    @Test fun rejectsOversizedAndEmpty() {
        assertThrows(SkillFormatException::class.java) { SkillParser.parse("a".repeat(SkillParser.MAX_CHARS + 1), "x") }
        assertThrows(SkillFormatException::class.java) { SkillParser.parse("---\nname: x\n---\n  \n", "x") }
    }

    @Test fun makesFolderSafeIds() {
        assertEquals("my-skill-2", SkillParser.idFor(" My Skill/2! "))
        assertEquals("skill", SkillParser.idFor("../.."))
        assertEquals("skill", SkillParser.idFor("Навык"))
    }

    @Test fun bundledSkillsAreValidAndShort() {
        // Unit tests run with the module directory as the working directory.
        val folders = File("../../skills").listFiles { f -> f.isDirectory }.orEmpty()
        assertTrue("expected the built-in skills, found ${folders.size}", folders.size >= 4)
        for (folder in folders) {
            val skill = SkillParser.parse(File(folder, "SKILL.md").readText(), "fallback")
            assertEquals(folder.name, skill.name)
            assertEquals(folder.name, SkillParser.idFor(skill.name))
            assertTrue("${folder.name}: description", skill.description.length in 20..140)
            assertTrue("${folder.name}: size", skill.instructions.length < 5000)
        }
    }
}

class SkillStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun store() = SkillStore(File(tmp.root, "skills"))

    @Test fun installsMarkdownAndPersistsState() {
        val store = store()
        val skill = store.install(ByteArrayInputStream(md("Alpha").toByteArray()), "ignored")
        assertEquals("alpha", skill.id)
        assertTrue(skill.enabled)
        assertFalse(skill.builtIn)

        store.setEnabled("alpha", false)
        val reopened = store()
        assertEquals(listOf(false), reopened.list().map { it.enabled })
        reopened.setEnabled("alpha", true)
        assertTrue(store().get("alpha")!!.enabled)

        reopened.delete("alpha")
        assertTrue(store().list().isEmpty())
    }

    @Test fun installsZipWithFilesAtRoot() {
        val store = store()
        val archive = zip(
            "SKILL.md" to md("Zipped", body = "See notes.md").toByteArray(),
            "notes.md" to "extra notes".toByteArray(),
            "ref/deep.txt" to "deep".toByteArray(),
            "image.png" to byteArrayOf(1, 0, 2),
            ".hidden" to "x".toByteArray(),
            "big.txt" to ByteArray(SkillArchive.MAX_FILE_BYTES + 1) { 'a'.code.toByte() },
        )
        val skill = store.install(ByteArrayInputStream(archive), "fallback")
        assertEquals("zipped", skill.id)
        assertEquals(listOf("notes.md", "ref/deep.txt"), skill.files)
        assertEquals("extra notes", store.readFile("zipped", "notes.md"))
        assertEquals("deep", store.readFile("zipped", "./ref/deep.txt"))
        assertNull(store.readFile("zipped", "../state.json"))
        assertNull(store.readFile("zipped", "SKILL.md"))
    }

    @Test fun installsGithubStyleArchiveAndNamesSkillAfterFolder() {
        val archive = zip(
            "repo-main/" to ByteArray(0),
            "repo-main/SKILL.md" to "Just instructions, no front matter.".toByteArray(),
            "repo-main/data.csv" to "a,b".toByteArray(),
        )
        val skill = store().install(ByteArrayInputStream(archive), "fallback")
        assertEquals("repo-main", skill.id)
        assertEquals(listOf("data.csv"), skill.files)
    }

    @Test fun rejectsZipSlipAndArchivesWithoutSkill() {
        val store = store()
        for (name in listOf("../evil.md", "a/../../evil.md", "/etc/passwd", "C:\\x.md", "..\\evil.md")) {
            val archive = zip("SKILL.md" to md("ok").toByteArray(), name to "x".toByteArray())
            assertThrows(name, SkillFormatException::class.java) { store.install(ByteArrayInputStream(archive), "f") }
        }
        assertFalse(File(tmp.root, "evil.md").exists())
        assertTrue(store.list().isEmpty())

        val two = zip("a/SKILL.md" to md("a").toByteArray(), "b/SKILL.md" to md("b").toByteArray())
        assertThrows(SkillFormatException::class.java) { store.install(ByteArrayInputStream(two), "f") }
        val none = zip("readme.md" to "hi".toByteArray())
        assertThrows(SkillFormatException::class.java) { store.install(ByteArrayInputStream(none), "f") }
    }

    @Test fun rejectsOversizedInput() {
        val store = store()
        val bigMd = ByteArray(SkillArchive.MAX_FILE_BYTES + 10) { 'a'.code.toByte() }
        assertThrows(SkillFormatException::class.java) { store.install(ByteArrayInputStream(bigMd), "f") }
        val bigManifest = zip("SKILL.md" to bigMd)
        assertThrows(SkillFormatException::class.java) { store.install(ByteArrayInputStream(bigManifest), "f") }
        val manyFiles = zip(*Array(300) { "f$it.txt" to "x".toByteArray() }, "SKILL.md" to md("m").toByteArray())
        assertThrows(SkillFormatException::class.java) { store.install(ByteArrayInputStream(manyFiles), "f") }
        assertThrows(SkillFormatException::class.java) { store.install(ByteArrayInputStream(byteArrayOf(1, 0, 3, 4, 5)), "f") }
    }

    @Test fun reinstallReplacesFilesAndKeepsDisabledState() {
        val store = store()
        store.install(SkillBundle(md("Same", body = "v1"), mapOf("old.txt" to "o".toByteArray()), "f"))
        store.setEnabled("same", false)
        val updated = store.install(SkillBundle(md("Same", body = "v2"), mapOf("new.txt" to "n".toByteArray()), "f"))
        assertEquals("v2", updated.instructions)
        assertEquals(listOf("new.txt"), updated.files)
        assertFalse(updated.enabled)
    }
}

private class FakeAssets(val files: MutableMap<String, String>) : SkillAssets {
    override fun list(path: String): List<String> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        return files.keys.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix).substringBefore('/') }.distinct()
    }

    override fun open(path: String): InputStream =
        files[path]?.byteInputStream() ?: throw FileNotFoundException(path)
}

class BuiltInSkillsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun installsOnceAndRespectsTheUser() {
        val store = SkillStore(tmp.root)
        val assets = FakeAssets(
            mutableMapOf(
                "catalog.json" to "{}",
                "images/logo.txt" to "not a skill",
                "one/SKILL.md" to md("one", body = "v1"),
                "one/extra.md" to "extra",
                "two/SKILL.md" to md("two", body = "v1"),
                "three/SKILL.md" to md("three", body = "v1"),
            ),
        )
        BuiltInSkills.installMissing(assets, store)
        assertEquals(listOf("one", "three", "two"), store.list().map { it.id })
        assertTrue(store.list().all { it.builtIn && it.enabled })
        assertEquals("extra", store.readFile("one", "extra.md"))

        // The user deletes one, replaces another, and the app ships new versions of all three.
        store.delete("one")
        store.installMarkdown(md("two", body = "mine"), "two")
        store.setEnabled("three", false)
        for (name in listOf("one", "two", "three")) assets.files["$name/SKILL.md"] = md(name, body = "v2")
        BuiltInSkills.installMissing(assets, store)

        assertNull(store.get("one"))
        assertEquals("mine", store.get("two")!!.instructions)
        assertFalse(store.get("two")!!.builtIn)
        assertEquals("v2", store.get("three")!!.instructions)
        assertFalse(store.get("three")!!.enabled)

        BuiltInSkills.restore(assets, store)
        assertEquals("v2", store.get("one")!!.instructions)
        assertEquals("mine", store.get("two")!!.instructions)
        assertTrue(store.get("three")!!.builtIn)
    }

    @Test fun doesNotTouchASkillTheUserInstalledFirst() {
        val store = SkillStore(tmp.root)
        store.installMarkdown(md("one", body = "mine"), "one")
        BuiltInSkills.installMissing(FakeAssets(mutableMapOf("skills/one/SKILL.md" to md("one", body = "shipped"))), store, "skills")
        assertEquals("mine", store.get("one")!!.instructions)
    }
}

class SkillLinkTest {
    @Test fun resolvesGithubPageLinks() {
        val folder = SkillLink.GithubFolder("acme", "skills", "main", "skills/pdf")
        assertEquals(folder, SkillLink.parse("https://github.com/acme/skills/tree/main/skills/pdf"))
        assertEquals(folder, SkillLink.parse(" github.com/acme/skills/tree/main/skills/pdf/ "))
        assertEquals(folder, SkillLink.parse("https://github.com/acme/skills/blob/main/skills/pdf/SKILL.md"))
        assertEquals(folder, SkillLink.parse("https://raw.githubusercontent.com/acme/skills/main/skills/pdf/SKILL.md"))
        assertEquals(folder, SkillLink.parse("https://raw.githubusercontent.com/acme/skills/refs/heads/main/skills/pdf/SKILL.md"))
        assertEquals("https://raw.githubusercontent.com/acme/skills/main/skills/pdf/SKILL.md", folder.skillUrl)
        assertEquals("https://api.github.com/repos/acme/skills/contents/skills/pdf?ref=main", folder.contentsUrl())
        assertEquals("https://api.github.com/repos/acme/skills/contents/skills/pdf/refs?ref=main", folder.contentsUrl("refs"))
        assertEquals("pdf", folder.fallbackName)

        val root = SkillLink.parse("https://github.com/acme/one-skill/tree/v2") as SkillLink.GithubFolder
        assertEquals("https://raw.githubusercontent.com/acme/one-skill/v2/SKILL.md", root.skillUrl)
        assertEquals("https://api.github.com/repos/acme/one-skill/contents/?ref=v2", root.contentsUrl())
        assertEquals("one-skill", root.fallbackName)
        assertEquals(SkillLink.GithubFolder("acme", "r", "main", ""), SkillLink.parse("https://github.com/acme/r/blob/main/SKILL.md"))
    }

    @Test fun resolvesDownloads() {
        assertEquals(
            SkillLink.Download("https://codeload.github.com/acme/one-skill/zip/HEAD", "one-skill"),
            SkillLink.parse("https://github.com/acme/one-skill.git"),
        )
        assertEquals(
            SkillLink.Download("https://raw.githubusercontent.com/acme/r/main/dist/pack.zip", "pack"),
            SkillLink.parse("https://github.com/acme/r/blob/main/dist/pack.zip"),
        )
        assertEquals(
            SkillLink.Download("https://github.com/acme/r/archive/refs/heads/main.zip", "r"),
            SkillLink.parse("https://github.com/acme/r/archive/refs/heads/main.zip"),
        )
        assertEquals(
            SkillLink.Download("https://example.com/skills/writer/SKILL.md", "writer"),
            SkillLink.parse("https://example.com/skills/writer/SKILL.md"),
        )
        assertEquals(SkillLink.Download("https://example.com/s/poet.zip?dl=1", "poet"), SkillLink.parse("example.com/s/poet.zip?dl=1"))
    }

    @Test fun rejectsWhatIsNotASkillLink() {
        for (link in listOf("", "ftp://example.com/a.zip", "https://github.com/acme", "https://github.com/acme/r/issues/1", "https://example.com", "not a link")) {
            assertNull(link, SkillLink.parse(link))
        }
    }

    private val listing = """[
        {"name":"SKILL.md","type":"file","size":10,"download_url":"https://raw.githubusercontent.com/a/r/main/s/SKILL.md"},
        {"name":"forms.md","type":"file","size":10,"download_url":"https://raw.githubusercontent.com/a/r/main/s/forms.md"},
        {"name":"unused.md","type":"file","size":10,"download_url":"https://raw.githubusercontent.com/a/r/main/s/unused.md"},
        {"name":"huge.md","type":"file","size":9999999,"download_url":"https://raw.githubusercontent.com/a/r/main/s/huge.md"},
        {"name":"evil.md","type":"file","size":10,"download_url":"https://evil.example/evil.md"},
        {"name":"reference","type":"dir","size":0,"download_url":null},
        {"name":"scripts","type":"dir","size":0,"download_url":null}
    ]"""
    private val skillMd = md("pdf", body = "Read forms.md, huge.md and evil.md. Details are in reference/api.md.")

    @Test fun picksReferencedEntriesFromAListing() {
        val entries = GithubContents.parse(listing)
        assertEquals(7, entries.size)
        assertNull(entries.first { it.name == "evil.md" }.downloadUrl)
        assertEquals(listOf("forms.md", "reference"), GithubContents.referenced(entries, skillMd).map { it.name })
        assertThrows(IOException::class.java) { GithubContents.parse("""{"message":"rate limit"}""") }
    }

    @Test fun installsAGithubFolderWithReferencedFiles() = runBlocking {
        val served = mapOf(
            "https://raw.githubusercontent.com/a/r/main/s/SKILL.md" to skillMd,
            "https://api.github.com/repos/a/r/contents/s?ref=main" to listing,
            "https://raw.githubusercontent.com/a/r/main/s/forms.md" to "forms",
            "https://api.github.com/repos/a/r/contents/s/reference?ref=main" to
                """[{"name":"api.md","type":"file","size":3,"download_url":"https://raw.githubusercontent.com/a/r/main/s/reference/api.md"}]""",
            "https://raw.githubusercontent.com/a/r/main/s/reference/api.md" to "api",
        )
        val requested = ArrayList<String>()
        val http = SkillHttp { url, _ ->
            requested += url
            served[url]?.toByteArray() ?: throw IOException("HTTP 404")
        }
        val store = SkillStore(File(System.getProperty("java.io.tmpdir"), "skills-link-${System.nanoTime()}"))
        val skill = SkillInstaller(store, http).fromLink("https://github.com/a/r/tree/main/s")
        assertEquals("pdf", skill.id)
        assertEquals(listOf("forms.md", "reference/api.md"), skill.files)
        assertEquals("api", store.readFile("pdf", "reference/api.md"))
        assertEquals(served.keys, requested.toSet())
    }

    @Test fun installsWithoutExtrasWhenTheApiIsUnavailableAndSniffsZips() = runBlocking<Unit> {
        val store = SkillStore(File(System.getProperty("java.io.tmpdir"), "skills-link-${System.nanoTime()}"))
        val http = SkillHttp { url, _ ->
            when {
                url.endsWith("/SKILL.md") -> skillMd.toByteArray()
                url == "https://example.com/get?id=7" -> zip("x/SKILL.md" to md("zipped").toByteArray())
                else -> throw IOException("HTTP 403")
            }
        }
        val installer = SkillInstaller(store, http)
        assertTrue(installer.fromLink("https://github.com/a/r/blob/main/s/SKILL.md").files.isEmpty())
        assertEquals("zipped", installer.fromLink("https://example.com/get?id=7").id)
        assertThrows(SkillFormatException::class.java) { runBlocking { installer.fromLink("nonsense") } }
        assertThrows(IOException::class.java) { runBlocking { installer.fromLink("https://example.com/missing.md") } }
    }
}

class SkillToolsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun skill(id: String, description: String = "Does $id", enabled: Boolean = true) =
        Skill(id, id, description, emptyList(), null, "Instructions of $id", emptyList(), enabled, false)

    @Test fun promptSectionListsOneLinePerSkill() {
        assertEquals("", skillsPromptSection(emptyList()))
        val section = skillsPromptSection(listOf(skill("alpha"), skill("beta", "x".repeat(500)), skill("gamma", "")))
        val lines = section.lines()
        assertEquals("## Skills", lines[0])
        assertTrue(lines[1].contains("use_skill"))
        assertEquals("- alpha — Does alpha", lines[2])
        assertTrue(lines[3].length < 160 && lines[3].endsWith("…"))
        assertEquals("- gamma", lines[4])
        assertEquals(5, lines.size)
        assertTrue(skillsPromptSection(List(100) { skill("s$it") }).length < 2500)
    }

    @Test fun useSkillReturnsInstructionsAndReadsFiles() = runBlocking {
        val store = SkillStore(tmp.root)
        store.install(SkillBundle("---\nname: Pdf Forms\ntools: [web_search]\n---\n" + "Long text. ".repeat(100), mapOf("forms.md" to "FORM NOTES".toByteArray()), "f"))
        store.installMarkdown(md("plain"), "plain")
        store.installMarkdown(md("off"), "off")
        store.setEnabled("off", false)
        val enabled = { store.list().filter { it.enabled } }
        val tools = skillTools(store, enabled, maxChars = 200)
        assertEquals(listOf("use_skill", "read_skill_file"), tools.map { it.spec.name })
        tools.forEach { JSONObject(it.spec.parametersSchema) }
        val log = AgentLog()

        val text = tools[0].run(JSONObject().put("name", "pdf forms"), log)
        assertTrue(text.startsWith("# Pdf Forms"))
        assertTrue(text.contains("[cut: the text is longer than 200 characters]"))
        assertTrue(text.contains("web_search") && text.contains("forms.md"))
        assertTrue(text.length < 700)
        assertEquals(1, log.entries.value.size)

        assertTrue(tools[0].run(JSONObject().put("name", "plain"), log).contains("Do the thing."))
        val missing = tools[0].run(JSONObject().put("name", "off"), log)
        assertTrue(missing.startsWith("Error:") && missing.contains("pdf-forms, plain") && !missing.contains("off,"))

        assertEquals("FORM NOTES", tools[1].run(JSONObject().put("name", "pdf-forms").put("file", "forms.md"), log))
        assertTrue(tools[1].run(JSONObject().put("name", "pdf-forms").put("file", "../state.json"), log).startsWith("Error:"))
    }

    @Test fun toolsFollowWhatIsEnabled() {
        val store = SkillStore(tmp.root)
        assertTrue(skillTools(store, { emptyList() }).isEmpty())
        assertEquals(listOf("use_skill"), skillTools(store, { listOf(skill("a")) }).map { it.spec.name })
        assertNotNull(skillTools(store, { listOf(skill("a")) }).single())
    }
}
