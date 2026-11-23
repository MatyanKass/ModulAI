package com.modulai.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    private val sample = """
        {"version":3,"future":true,"models":[
          {"id":"qwen3-4b-q4km","name":"Qwen3 4B","category":"chat","repo":"unsloth/Qwen3-4B-GGUF",
           "file":"Qwen3-4B-Q4_K_M.gguf","sizeBytes":2497281312,"params":"4B","quant":"Q4_K_M","minRamGb":6,
           "languages":["ru","en"],"uncensored":false,"thinking":true,"tools":true,
           "description":{"ru":"Описание","en":"Description"},"presets":{"x":1}},
          {"id":"painter","category":"image","repo":"a/b","file":"sub/p.gguf","description":{"de":"Bild"}},
          {"id":"painter","repo":"a/b","file":"duplicate.gguf"},
          {"id":"no-file","repo":"a/b"},
          {"id":"not-gguf","repo":"a/b","file":"model.safetensors"},
          "garbage"
        ]}
    """

    @Test fun parsesEntries() {
        val catalog = Catalog.parse(sample)!!
        assertEquals(3, catalog.version)
        assertEquals(listOf("qwen3-4b-q4km", "painter"), catalog.models.map { it.id })

        val qwen = catalog.models[0]
        assertEquals("Qwen3 4B", qwen.name)
        assertEquals(ModelCategory.CHAT, qwen.category)
        assertEquals(2497281312L, qwen.sizeBytes)
        assertEquals("4B", qwen.params)
        assertEquals("Q4_K_M", qwen.quant)
        assertEquals(6, qwen.minRamGb)
        assertEquals(listOf("ru", "en"), qwen.languages)
        assertTrue(qwen.thinking && qwen.tools && !qwen.uncensored)
        assertEquals(
            "https://huggingface.co/unsloth/Qwen3-4B-GGUF/resolve/main/Qwen3-4B-Q4_K_M.gguf?download=true",
            qwen.link.downloadUrl,
        )
    }

    @Test fun toleratesUnknownCategoriesAndMissingFields() {
        val painter = Catalog.parse(sample)!!.models[1]
        assertEquals(ModelCategory.OTHER, painter.category)
        assertEquals("painter", painter.name)
        assertEquals(0L, painter.sizeBytes)
        assertEquals(emptyList<String>(), painter.languages)
        assertEquals("p.gguf", painter.link.fileName)
    }

    @Test fun picksDescriptionLanguage() {
        val (qwen, painter) = Catalog.parse(sample)!!.models
        assertEquals("Описание", qwen.describe("ru"))
        assertEquals("Description", qwen.describe("fr"))
        assertEquals("Bild", painter.describe("ru"))
        assertEquals("", qwen.copy(description = emptyMap()).describe("en"))
    }

    @Test fun acceptsEmptyAndRejectsBrokenCatalogs() {
        assertEquals(Catalog(1, emptyList()), Catalog.parse("""{"version":1,"models":[]}"""))
        assertNull(Catalog.parse(""))
        assertNull(Catalog.parse("<html>404</html>"))
        assertNull(Catalog.parse("""{"version":2}"""))
        assertNull(Catalog.parse("""[1,2]"""))
    }

    @Test fun cachedCopyWinsOnlyWhenNotOlder() {
        val bundled = Catalog(2, emptyList())
        val older = Catalog(1, emptyList())
        val same = Catalog(2, emptyList())
        val newer = Catalog(3, emptyList())
        assertSame(bundled, Catalog.newest(bundled, null))
        assertSame(bundled, Catalog.newest(bundled, older))
        assertSame(same, Catalog.newest(bundled, same))
        assertSame(newer, Catalog.newest(bundled, newer))
    }

    @Test fun ratesRamFit() {
        val gib = 1L shl 30
        val phone8 = (7.4 * gib).toLong()
        assertEquals(RamFit.FITS, ramFit(2_497_281_312, phone8))
        assertEquals(RamFit.TIGHT, ramFit(5 * gib, phone8))
        assertEquals(RamFit.TOO_LARGE, ramFit(7 * gib, phone8))
        // The catalog's requirement is in marketing gigabytes: 7.4 GiB counts as 8.
        assertEquals(RamFit.FITS, ramFit(2_497_281_312, phone8, minRamGb = 8))
        assertEquals(RamFit.TOO_LARGE, ramFit(2_497_281_312, phone8, minRamGb = 12))
    }
}
