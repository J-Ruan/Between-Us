package cn.shiyan.core

import java.security.SecureRandom
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Conservative byte-level bound for Qwen's byte-BPE when native tokenizer is unavailable. UTF-8
 * byte count is an upper bound for ordinary text; fixed template overhead is reserved separately.
 */
object Budget {
    const val CONTEXT = 4096
    const val OUTPUT = 640
    const val OVERHEAD = 768
    const val INPUT = 2200

    fun tokens(text: String) = text.toByteArray(Charsets.UTF_8).size

    fun requireFits(prompt: String) {
        require(tokens(prompt) + OUTPUT + OVERHEAD <= CONTEXT) { "输入超过保守 Token 预算，请缩小范围" }
    }

    fun batches(items: List<Source>, limit: Int = INPUT): List<List<Source>> {
        require(limit > 0)
        val result = mutableListOf<List<Source>>()
        var batch = mutableListOf<Source>()
        var size = 0
        for (item in items) {
            // Split oversized messages on Unicode code point boundaries; preserve the original ID.
            for (part in split(item, limit)) {
                val cost = tokens(part.render())
                if (size + cost > limit && batch.isNotEmpty()) {
                    result += batch
                    batch = mutableListOf()
                    size = 0
                }
                batch += part
                size += cost
            }
        }
        if (batch.isNotEmpty()) result += batch
        return result
    }

    private fun split(source: Source, limit: Int): List<Source> {
        if (tokens(source.render()) <= limit) return listOf(source)
        val room = limit - tokens(source.copy(text = "").render())
        require(room >= 4) { "消息元数据超过预算" }
        val out = mutableListOf<Source>()
        val text = StringBuilder()
        var bytes = 0
        source.text.codePoints().forEach { cp ->
            val value = String(Character.toChars(cp))
            val n = tokens(value)
            if (bytes + n > room) {
                out += source.copy(text = text.toString())
                text.clear()
                bytes = 0
            }
            text.append(value)
            bytes += n
        }
        if (text.isNotEmpty()) out += source.copy(text = text.toString())
        return out
    }
}

data class Source(
    val id: String,
    val text: String,
    val sender: String = "待确认",
    val date: String? = null,
) {
    fun render() = "[$id] ${date ?: "日期待确认"} $sender: $text\n"
}

data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int)

data class Parsed(
    val text: String,
    val side: String,
    val date: String?,
    val time: String?,
    val top: Int,
)

object ChatParser {
    private val date = Regex("^(20\\d{2})[年/.-](\\d{1,2})[月/.-](\\d{1,2})日?(?:\\s.*)?$")
    private val time = Regex("^(?:上午|下午|晚上)?\\s?([0-2]?\\d:[0-5]\\d)$")

    fun parse(lines: List<OcrLine>, width: Int): List<Parsed> {
        require(width > 0)
        var day: String? = null
        var clock: String? = null
        val result = mutableListOf<Parsed>()
        for (line in lines.sortedWith(compareBy({ it.top }, { it.left }))) {
            val text = line.text.trim()
            if (text.isEmpty()) continue
            val d = date.matchEntire(text)
            if (d != null) {
                day =
                    runCatching {
                            LocalDate.of(
                                    d.groupValues[1].toInt(),
                                    d.groupValues[2].toInt(),
                                    d.groupValues[3].toInt(),
                                )
                                .toString()
                        }
                        .getOrNull()
                clock = null
                continue
            }
            if (time.matches(text)) {
                clock = text
                continue
            }
            val center = (line.left + line.right) / 2.0 / width
            val side = if (center < .45) "左侧" else if (center > .55) "右侧" else "待确认"
            result += Parsed(text, side, day, clock, line.top)
        }
        return result
    }

    fun normalize(text: String) = text.replace(Regex("\\s+"), "").trim()

    /** Only suggest overlaps; never silently remove repeated short phrases. */
    fun overlap(previous: List<Parsed>, next: List<Parsed>): Int {
        for (n in minOf(previous.size, next.size) downTo 2) {
            if (
                previous.takeLast(n).zip(next.take(n)).all { (a, b) ->
                    normalize(a.text) == normalize(b.text) &&
                        a.side == b.side &&
                        a.date == b.date &&
                        a.time == b.time
                }
            )
                return n
        }
        return 0
    }
}

object References {
    private val ids = Regex("\\[([A-Za-z0-9_:-]+)]")

    fun extract(text: String): Set<String> = ids.findAll(text).map { it.groupValues[1] }.toSet()

    fun validate(text: String, allowed: Set<String>): Set<String> {
        val found = extract(text)
        require(found.all { it in allowed }) { "生成结果引用了不存在的消息，已拒绝保存" }
        return found
    }
}

object EncryptedBackup {
    private val magic = "SHIYAN01".toByteArray()

    fun encrypt(data: ByteArray, password: CharArray): ByteArray {
        require(password.size >= 8) { "备份密码至少 8 个字符" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(128, iv))
        c.updateAAD(magic)
        return magic + salt + iv + c.doFinal(data)
    }

    fun decrypt(data: ByteArray, password: CharArray): ByteArray {
        require(data.size >= 52 && data.copyOfRange(0, 8).contentEquals(magic)) { "不支持的备份格式" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(
            Cipher.DECRYPT_MODE,
            key(password, data.copyOfRange(8, 24)),
            GCMParameterSpec(128, data.copyOfRange(24, 36)),
        )
        c.updateAAD(magic)
        return c.doFinal(data.copyOfRange(36, data.size))
    }

    private fun key(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 210000, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }
}

fun htmlEscape(value: String) =
    value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")
