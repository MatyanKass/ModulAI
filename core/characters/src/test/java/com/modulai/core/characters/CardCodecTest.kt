package com.modulai.core.characters

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

class CardCodecTest {
    private val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun int32(value: Int) =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.ISO_8859_1)
        val crc = CRC32().apply { update(typeBytes); update(data) }.value.toInt()
        return int32(data.size) + typeBytes + data + int32(crc)
    }

    // 1x1 greyscale image header; pixel data is irrelevant to the chunk reader.
    private val ihdr = chunk("IHDR", int32(1) + int32(1) + byteArrayOf(8, 0, 0, 0, 0))
    private val iend = chunk("IEND", ByteArray(0))

    private fun png(vararg chunks: ByteArray) = signature + ihdr + chunks.fold(ByteArray(0)) { all, c -> all + c } + iend

    private fun base64(json: String) = Base64.getEncoder().encodeToString(json.toByteArray()).toByteArray(Charsets.ISO_8859_1)

    private fun text(keyword: String, json: String) = chunk("tEXt", keyword.toByteArray() + 0 + base64(json))

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater().apply { setInput(data); finish() }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun v2(name: String, spec: String = "chara_card_v2") =
        """{"spec":"$spec","spec_version":"2.0","data":{"name":"$name","description":"Desc of {{char}}",
           "personality":"calm","scenario":"a tavern","first_mes":"Hello, {{user}}!","mes_example":"<START>\n{{char}}: hi",
           "creator_notes":"notes","system_prompt":"","post_history_instructions":"stay brief",
           "alternate_greetings":["Hi again","  "],"tags":["fantasy"," elf ",""],"creator":"someone",
           "character_book":{"entries":[
             {"keys":["sword","blade"],"content":"The sword is cursed.","enabled":true,"insertion_order":20},
             {"keys":["king"],"content":"The king is dead.","enabled":false,"insertion_order":10,"constant":true}
           ]}}}"""

    private fun failsWith(error: CardError, block: () -> Unit) {
        try {
            block()
            fail("expected $error")
        } catch (e: CardFormatException) {
            assertEquals(error, e.error)
        }
    }

    @Test
    fun parsesV1() {
        val card = CardCodec.parseJson(
            """{"name":"Aria","description":"An elf","personality":"shy","scenario":null,"first_mes":"Hi","mes_example":"ex"}""",
        )
        assertEquals("Aria", card.name)
        assertEquals("An elf", card.description)
        assertEquals("shy", card.personality)
        assertEquals("", card.scenario)
        assertEquals("Hi", card.firstMessage)
        assertEquals("ex", card.exampleDialogue)
        assertTrue(card.lorebook.isEmpty())
    }

    @Test
    fun parsesV2WithLorebook() {
        val card = CardCodec.parseJson(v2("Borin"))
        assertEquals("Borin", card.name)
        assertEquals("a tavern", card.scenario)
        assertEquals("stay brief", card.postHistoryInstructions)
        assertEquals(listOf("fantasy", "elf"), card.tags)
        assertEquals(listOf("Hello, {{user}}!", "Hi again"), card.greetings)
        assertEquals(2, card.lorebook.size)
        assertEquals(listOf("sword", "blade"), card.lorebook[0].keys)
        assertEquals(20, card.lorebook[0].insertionOrder)
        assertFalse(card.lorebook[1].enabled)
        assertTrue(card.lorebook[1].constant)
    }

    @Test
    fun parsesV3AndWorldInfoStyleLorebook() {
        val card = CardCodec.parseJson(
            """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"Cyra","first_mes":"Yo",
               "group_only_greetings":[],"character_book":{"entries":{"0":{"key":["moon"],"content":"Two moons.","order":5,"disable":true}}}}}""",
        )
        assertEquals("Cyra", card.name)
        assertEquals(LorebookEntry(listOf("moon"), "Two moons.", enabled = false, constant = false, insertionOrder = 5), card.lorebook.single())
    }

    @Test
    fun rejectsJsonThatIsNotACard() {
        failsWith(CardError.BAD_JSON) { CardCodec.parseJson("not json at all") }
        failsWith(CardError.BAD_JSON) { CardCodec.parseJson("[1,2]") }
        failsWith(CardError.NOT_A_CARD) { CardCodec.parseJson("""{"hello":"world"}""") }
        failsWith(CardError.NOT_A_CARD) { CardCodec.parseJson("""{"spec":"chara_card_v2","data":{"name":"  "}}""") }
        failsWith(CardError.BAD_JSON) { CardCodec.parse(byteArrayOf(0, 1, 2, 3, -1, -2)) }
    }

    @Test
    fun readsCardFromPngTextChunk() {
        val card = CardCodec.parse(png(text("chara", v2("Dana"))))
        assertEquals("Dana", card.name)
        assertEquals("calm", card.personality)
    }

    @Test
    fun prefersCcv3OverChara() {
        val bytes = png(text("chara", v2("Old")), chunk("tEXt", "Comment".toByteArray() + 0 + "x".toByteArray()), text("ccv3", v2("New", "chara_card_v3")))
        assertEquals("New", CardCodec.parse(bytes).name)
    }

    @Test
    fun readsInternationalAndCompressedChunks() {
        val json = v2("Élodie")
        val plain = chunk("iTXt", "ccv3".toByteArray() + byteArrayOf(0, 0, 0) + "en".toByteArray() + 0 + 0 + base64(json))
        assertEquals("Élodie", CardCodec.parse(png(plain)).name)
        val packed = chunk("iTXt", "ccv3".toByteArray() + byteArrayOf(0, 1, 0, 0, 0) + deflate(base64(json)))
        assertEquals("Élodie", CardCodec.parse(png(packed)).name)
        val zipped = chunk("zTXt", "chara".toByteArray() + byteArrayOf(0, 0) + deflate(base64(json)))
        assertEquals("Élodie", CardCodec.parse(png(zipped)).name)
    }

    @Test
    fun malformedPngGivesAClearError() {
        val good = png(text("chara", v2("Dana")))
        failsWith(CardError.NO_CARD_IN_PNG) { CardCodec.parse(png()) }
        // Cut in the middle of the text chunk, and right after the signature.
        failsWith(CardError.BAD_PNG) { CardCodec.parse(good.copyOf(good.size - 40)) }
        failsWith(CardError.BAD_PNG) { CardCodec.parse(signature) }
        // No IEND chunk at all.
        failsWith(CardError.BAD_PNG) { CardCodec.parse(signature + ihdr) }
        // A chunk claiming 4 GB and one claiming 2 GB (negative as a signed int).
        for (length in listOf(byteArrayOf(-1, -1, -1, -1), byteArrayOf(0x7F, -1, -1, -1), byteArrayOf(-128, 0, 0, 0))) {
            failsWith(CardError.BAD_PNG) { CardCodec.parse(signature + ihdr + length + "tEXt".toByteArray() + ByteArray(16)) }
        }
        failsWith(CardError.BAD_JSON) { CardCodec.parse(png(chunk("tEXt", "chara".toByteArray() + 0 + "@@not base64@@".toByteArray()))) }
        failsWith(CardError.BAD_JSON) { CardCodec.parse(png(chunk("tEXt", "chara".toByteArray() + 0 + base64("oops")))) }
        failsWith(CardError.BAD_PNG) { CardCodec.parse(png(chunk("zTXt", "chara".toByteArray() + byteArrayOf(0, 0, 1, 2, 3, 4)))) }
        val truncatedDeflate = deflate(base64(v2("Dana"))).let { it.copyOf(it.size / 2) }
        failsWith(CardError.BAD_PNG) { CardCodec.parse(png(chunk("zTXt", "chara".toByteArray() + byteArrayOf(0, 0) + truncatedDeflate))) }
    }

    @Test
    fun textChunkWithoutSeparatorIsIgnored() {
        assertTrue(PngText.read(png(chunk("tEXt", "chara".toByteArray()), chunk("iTXt", "ccv3".toByteArray() + byteArrayOf(0, 0)))).isEmpty())
    }

    @Test
    fun exportsV2AndReadsItBack() {
        val card = CardCodec.parseJson(v2("Borin")).copy(name = "Borin \"the\" Bold", description = "line1\nline2 — ёж")
        val json = CardCodec.toV2Json(card)
        val root = JSONObject(json)
        assertEquals("chara_card_v2", root.getString("spec"))
        assertEquals("2.0", root.getString("spec_version"))
        assertEquals("Hello, {{user}}!", root.getJSONObject("data").getString("first_mes"))
        // Blank greetings and tags are dropped on import, everything else survives.
        assertEquals(card, CardCodec.parseJson(json))
    }

    @Test
    fun boundedReadStopsAtTheLimit() {
        assertEquals(100, CharacterStore.readAtMost(ByteArray(100).inputStream(), 100).size)
        failsWith(CardError.TOO_LARGE) { CharacterStore.readAtMost(ByteArray(200_000).inputStream(), 100_000) }
    }
}
