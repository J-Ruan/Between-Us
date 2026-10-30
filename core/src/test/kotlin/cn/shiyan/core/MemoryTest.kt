package cn.shiyan.core

import javax.crypto.AEADBadTagException
import org.junit.Assert.*
import org.junit.Test

class MemoryTest {
    @Test
    fun byteBudgetIncludesChineseAndEmoji() {
        assertEquals(3, Budget.tokens("中"))
        assertEquals(4, Budget.tokens("🌷"))
        assertEquals(3, Budget.tokens("abc"))
    }

    @Test
    fun fullBudgetReservesOutputAndTemplate() {
        Budget.requireFits("中".repeat(800))
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedPromptRejected() {
        Budget.requireFits("中".repeat(2000))
    }

    @Test
    fun process100() {
        verify(100)
    }

    @Test
    fun process1000() {
        verify(1000)
    }

    @Test
    fun process10000() {
        verify(10000)
    }

    private fun verify(n: Int) {
        val source =
            (0 until n).map {
                Source(
                    "msg_$it",
                    "这是第 $it 条聊天消息，今天也很开心。",
                    "小言",
                    if (it % 3 == 0) null else "2026-09-25",
                )
            }
        val batches = Budget.batches(source)
        assertEquals(source, batches.flatten())
        assertTrue(batches.all { b -> b.sumOf { Budget.tokens(it.render()) } <= Budget.INPUT })
        assertTrue(batches.all { it.isNotEmpty() })
        assertEquals(n, batches.flatten().map { it.id }.toSet().size)
    }

    @Test
    fun emptyInputCreatesNoBatches() {
        assertTrue(Budget.batches(emptyList()).isEmpty())
    }

    @Test
    fun exactBoundaryPreservesMessage() {
        val s = Source("one", "你好")
        assertEquals(listOf(listOf(s)), Budget.batches(listOf(s), Budget.tokens(s.render())))
    }

    @Test
    fun longSingleMessageKeepsIdAndUnicode() {
        val text = "你好🌷！".repeat(10000)
        val parts = Budget.batches(listOf(Source("long", text))).flatten()
        assertTrue(parts.size > 1)
        assertEquals(text, parts.joinToString("") { it.text })
        assertTrue(parts.all { it.id == "long" })
        assertTrue(parts.all { Budget.tokens(it.render()) <= Budget.INPUT })
    }

    @Test
    fun missingDatesStayUnknown() {
        assertNull(ChatParser.parse(listOf(OcrLine("你好", 10, 20, 80, 40)), 1000).single().date)
    }

    @Test
    fun datesAndTimesAreMetadata() {
        val p =
            ChatParser.parse(
                    listOf(
                        OcrLine("2026年9月25日", 0, 1, 500, 20),
                        OcrLine("12:30", 0, 25, 500, 45),
                        OcrLine("晚安", 700, 50, 950, 80),
                    ),
                    1000,
                )
                .single()
        assertEquals("2026-09-25", p.date)
        assertEquals("12:30", p.time)
        assertEquals("右侧", p.side)
    }

    @Test
    fun invalidDateNotFabricated() {
        assertNull(
            ChatParser.parse(
                    listOf(OcrLine("2026-02-30", 0, 0, 500, 10), OcrLine("你好", 10, 20, 80, 40)),
                    1000,
                )
                .single()
                .date
        )
    }

    @Test
    fun stableVerticalOrderAndSides() {
        val p =
            ChatParser.parse(
                listOf(
                    OcrLine("右", 800, 50, 950, 80),
                    OcrLine("左", 10, 10, 100, 30),
                    OcrLine("中", 450, 40, 550, 45),
                ),
                1000,
            )
        assertEquals(listOf("左侧", "待确认", "右侧"), p.map { it.side })
    }

    @Test
    fun repeatedShortPhraseNeverDeduplicated() {
        val p = Parsed("好的", "左侧", null, null, 1)
        assertEquals(0, ChatParser.overlap(listOf(p), listOf(p)))
    }

    @Test
    fun adjacentOverlapSuggestedWithoutDeleting() {
        val p = Parsed("晚安", "左侧", null, null, 1)
        val q = p.copy(text = "明天见", side = "右侧")
        val next = listOf(p, q, p)
        assertEquals(2, ChatParser.overlap(listOf(p, q), next))
        assertEquals(3, next.size)
    }

    @Test
    fun overlapRespectsSenderAndTime() {
        val p = Parsed("晚安", "左侧", null, "22:00", 1)
        val q = p.copy(text = "明天见")
        assertEquals(0, ChatParser.overlap(listOf(p, q), listOf(p.copy(time = "23:00"), q)))
    }

    @Test
    fun normalizeWhitespace() {
        assertEquals("你好世界", ChatParser.normalize("你 好\n世界"))
    }

    @Test
    fun citationsValidated() {
        assertEquals(setOf("msg_1"), References.validate("你好 [msg_1]", setOf("msg_1")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun fabricatedCitationRejected() {
        References.validate("[missing]", setOf("msg_1"))
    }

    @Test
    fun backupRoundTrip() {
        val source = "我们的聊天🌷".repeat(1000).toByteArray()
        val pass = "private password".toCharArray()
        val archive = EncryptedBackup.encrypt(source, pass)
        assertFalse(archive.contentEquals(source))
        assertArrayEquals(source, EncryptedBackup.decrypt(archive, pass))
    }

    @Test
    fun randomSaltAndNonce() {
        val a = EncryptedBackup.encrypt(byteArrayOf(1), "password".toCharArray())
        val b = EncryptedBackup.encrypt(byteArrayOf(1), "password".toCharArray())
        assertFalse(a.contentEquals(b))
    }

    @Test(expected = AEADBadTagException::class)
    fun wrongPasswordRejected() {
        val a = EncryptedBackup.encrypt(byteArrayOf(1), "password".toCharArray())
        EncryptedBackup.decrypt(a, "incorrect".toCharArray())
    }

    @Test(expected = AEADBadTagException::class)
    fun tamperedBackupRejected() {
        val a = EncryptedBackup.encrypt(byteArrayOf(1), "password".toCharArray())
        a[a.lastIndex] = (a.last().toInt() xor 1).toByte()
        EncryptedBackup.decrypt(a, "password".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortPasswordRejected() {
        EncryptedBackup.encrypt(byteArrayOf(1), "short".toCharArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBackupVersionRejected() {
        EncryptedBackup.decrypt(ByteArray(100), "password".toCharArray())
    }

    @Test
    fun htmlEscapesUserContent() {
        assertEquals("&lt;script&gt;&amp;&quot;&#39;", htmlEscape("<script>&\"'"))
    }
}
