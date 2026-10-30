package cn.shiyan.betweenus

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import cn.shiyan.core.Budget
import com.google.ai.edge.litertlm.*
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalAi(private val context: Context, private val dao: MemoryDao) {
    private val mutex = Mutex()
    private var engine: Engine? = null
    @Volatile private var active: com.google.ai.edge.litertlm.Conversation? = null
    val modelFile
        get() = File(context.noBackupFilesDir, "models/Qwen3-0.6B.litertlm")

    val available
        get() = modelFile.isFile

    private fun load(): Engine {
        engine?.let {
            return it
        }
        check(available) { "请先导入 Qwen3-0.6B.litertlm 模型" }
        Engine.setNativeMinLogSeverity(LogSeverity.ERROR)
        val new =
            Engine(
                EngineConfig(
                    modelPath = modelFile.path,
                    backend = Backend.CPU(threadCount = 4),
                    maxNumTokens = 4096,
                    cacheDir = context.cacheDir.path,
                )
            )
        try {
            new.initialize()
            engine = new
            return new
        } catch (t: Throwable) {
            new.close()
            throw t
        }
    }

    suspend fun install(uri: Uri, expectedHash: String) =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                require(expectedHash.matches(Regex("[a-fA-F0-9]{64}"))) {
                    "请填写模型官方文件页面的 SHA-256，以验证完整性"
                }
                val name =
                    context.contentResolver
                        .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else "" }
                        .orEmpty()
                require(name.endsWith(".litertlm", true)) { "请选择 .litertlm 文件" }
                modelFile.parentFile!!.mkdirs()
                val tmp = File(modelFile.parentFile, "import.part")
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    context.contentResolver.openInputStream(uri)!!.use { input ->
                        tmp.outputStream().use { out ->
                            val buffer = ByteArray(1024 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= 900_000_000) { "文件过大；仅支持 Qwen3-0.6B" }
                                digest.update(buffer, 0, n)
                                out.write(buffer, 0, n)
                            }
                        }
                    }
                    require(tmp.length() in 400_000_000..900_000_000) { "文件大小不符合指定模型" }
                    val hash = digest.digest().joinToString("") { "%02x".format(it) }
                    require(hash.equals(expectedHash, true)) { "SHA-256 不匹配，文件已拒绝" }
                    // Native loader validates the actual container and tokenizer; smoke inference
                    // verifies execution.
                    val candidate =
                        Engine(
                            EngineConfig(
                                modelPath = tmp.path,
                                backend = Backend.CPU(threadCount = 4),
                                maxNumTokens = 4096,
                            )
                        )
                    candidate.use { e ->
                        e.initialize()
                        e.createConversation(
                                ConversationConfig(
                                    maxOutputToken = 32,
                                    thinkingConfig = ThinkingConfig(false),
                                )
                            )
                            .use { c ->
                                val reply = c.sendMessage("请用中文说你好。/no_think").toString()
                                check(reply.isNotBlank()) { "模型测试未返回文字" }
                            }
                    }
                    engine?.close()
                    engine = null
                    check(tmp.renameTo(modelFile)) { "模型保存失败" }
                    dao.put(
                        ModelProfile(
                            size = modelFile.length(),
                            sha256 = hash,
                            status = "已通过本机加载与推理检查",
                        )
                    )
                } finally {
                    tmp.delete()
                }
            }
        }

    fun generate(prompt: String): Flow<String> =
        flow {
                Budget.requireFits(prompt)
                mutex.withLock {
                    val c =
                        load()
                            .createConversation(
                                ConversationConfig(
                                    maxOutputToken = Budget.OUTPUT,
                                    thinkingConfig = ThinkingConfig(false),
                                )
                            )
                    active = c
                    try {
                        c.sendMessageAsync(prompt).collect { emit(it.toString()) }
                    } finally {
                        c.cancelProcess()
                        c.close()
                        active = null
                    }
                }
            }
            .flowOn(Dispatchers.IO)

    fun cancel() {
        active?.cancelProcess()
    }

    suspend fun remove() =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                engine?.close()
                engine = null
                modelFile.delete()
                dao.clearModel()
            }
        }
}
