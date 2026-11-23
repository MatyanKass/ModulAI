package com.modulai.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HfTest {
    @Test fun parsesFileUrls() {
        val expected = HfLink.File("bartowski/Qwen_Qwen3-4B-GGUF", "main", "Qwen_Qwen3-4B-Q4_K_M.gguf")
        assertEquals(expected, HfLink.parse("https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf?download=true"))
        assertEquals(expected, HfLink.parse(" huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/blob/main/Qwen_Qwen3-4B-Q4_K_M.gguf "))
        assertEquals(
            "https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/main/Qwen_Qwen3-4B-Q4_K_M.gguf?download=true",
            expected.downloadUrl,
        )
    }

    @Test fun keepsSubdirectories() {
        val link = HfLink.parse("https://huggingface.co/a/b/resolve/v1/sub/dir/m.gguf") as HfLink.File
        assertEquals("sub/dir/m.gguf", link.path)
        assertEquals("v1", link.revision)
        assertEquals("m.gguf", link.fileName)
    }

    @Test fun parsesRepos() {
        assertEquals(HfLink.Repo("org/repo"), HfLink.parse("org/repo"))
        assertEquals(HfLink.Repo("org/repo"), HfLink.parse("https://huggingface.co/org/repo/"))
        assertEquals(HfLink.Repo("org/repo"), HfLink.parse("https://hf.co/org/repo/tree/main"))
    }

    @Test fun rejectsOtherInput() {
        assertNull(HfLink.parse("hello"))
        assertNull(HfLink.parse(""))
        assertNull(HfLink.parse("https://example.com/org/repo/resolve/main/m.gguf"))
        assertNull(HfLink.parse("org/re po"))
        assertNull(HfLink.parse("https://example.com/repo"))
    }

    @Test fun listsOnlyChatGgufFilesSmallestFirst() {
        val json = """
            [{"type":"directory","path":"sub","size":0},
             {"type":"file","path":"README.md","size":10},
             {"type":"file","path":"m-Q8_0.gguf","size":135,"lfs":{"size":8000}},
             {"type":"file","path":"m-Q4_K_M.gguf","size":135,"lfs":{"size":4000}},
             {"type":"file","path":"mmproj-m-f16.gguf","size":135,"lfs":{"size":900}}]
        """
        assertEquals(listOf(HfFile("m-Q4_K_M.gguf", 4000), HfFile("m-Q8_0.gguf", 8000)), HfApi.parseTree(json))
    }

    @Test fun parsesSearchResults() {
        // Trimmed from a live answer of /api/models?search=qwen3&filter=gguf&sort=downloads&direction=-1.
        val json = """
            [{"_id":"6a7d8038d813eea1a14a8db4","id":"unsloth/Qwen3.8-27B-GGUF","likes":6308,"private":false,
              "downloads":7683660,"tags":["gguf","unsloth","license:apache-2.0","conversational"],
              "createdAt":"2026-08-13T08:28:40.000Z","modelId":"unsloth/Qwen3.8-27B-GGUF"},
             {"_id":"6a9e86d1dab7f88030e2af20","id":"ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF","likes":1327,
              "private":false,"downloads":7533868,"tags":["gguf"],"pipeline_tag":"image-text-to-text",
              "library_name":"gguf","createdAt":"2026-09-07T09:41:37.000Z"},
             {"_id":"x","likes":1}]
        """
        assertEquals(
            listOf(
                HfRepo("unsloth/Qwen3.8-27B-GGUF", 7683660, 6308),
                HfRepo("ISTA-DASLab/Qwen3.8-Flash-Next-GSQ-RCO-GGUF", 7533868, 1327),
            ),
            HfApi.parseSearch(json),
        )
        assertEquals(emptyList<HfRepo>(), HfApi.parseSearch("[]"))
    }

    @Test fun sanitizesFileNames() {
        assertEquals("a_b.gguf", ModelStore.sanitize("a b.gguf"))
        assertEquals("_etc_passwd", ModelStore.sanitize("../etc/passwd"))
    }
}
