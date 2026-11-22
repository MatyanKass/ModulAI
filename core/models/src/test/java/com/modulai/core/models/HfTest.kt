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

    @Test fun sanitizesFileNames() {
        assertEquals("a_b.gguf", ModelStore.sanitize("a b.gguf"))
        assertEquals("_etc_passwd", ModelStore.sanitize("../etc/passwd"))
    }
}
