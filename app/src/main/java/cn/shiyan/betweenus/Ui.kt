@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package cn.shiyan.betweenus

import android.content.Context
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import java.io.File
import java.time.LocalDate

@Composable
fun ShiyanUi(vm: AppViewModel = viewModel(), unlocked: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", false)) }
    val colors =
        if (dark)
            darkColorScheme(
                primary = Color(0xFFE5B5B9),
                background = Color(0xFF201D1C),
                surface = Color(0xFF282322),
            )
        else
            lightColorScheme(
                primary = Color(0xFF945B66),
                background = Color(0xFFFFFAF3),
                surface = Color(0xFFFFFAF3),
                secondaryContainer = Color(0xFFF4E8DF),
            )
    MaterialTheme(colorScheme = colors) {
        val messages by vm.messages.collectAsStateWithLifecycle()
        val chapters by vm.chapters.collectAsStateWithLifecycle()
        val events by vm.events.collectAsStateWithLifecycle()
        val tasks by vm.tasks.collectAsStateWithLifecycle()
        val summaries by vm.summaries.collectAsStateWithLifecycle()
        val model by vm.model.collectAsStateWithLifecycle()
        val status by vm.status.collectAsStateWithLifecycle()
        val busy by vm.busy.collectAsStateWithLifecycle()
        val stream by vm.stream.collectAsStateWithLifecycle()
        val assets by vm.assets.collectAsStateWithLifecycle()
        var tab by remember { mutableIntStateOf(0) }
        var section by remember { mutableStateOf("") }
        var query by remember { mutableStateOf("") }
        var start by remember { mutableStateOf("") }
        var end by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(setOf<String>()) }
        var onlySelected by remember { mutableStateOf(false) }
        var edit by remember { mutableStateOf<Message?>(null) }
        var chapterEdit by remember { mutableStateOf<StoryChapter?>(null) }
        var dialog by remember { mutableStateOf("") }
        var hash by remember { mutableStateOf("") }
        var password by remember { mutableStateOf("") }
        var eventTitle by remember { mutableStateOf("") }
        var payload by remember { mutableStateOf<ByteArray?>(null) }
        val save =
            rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("application/octet-stream")
            ) { uri ->
                if (uri != null)
                    vm.run("正在导出") {
                        context.contentResolver.openOutputStream(uri)!!.use {
                            it.write(payload ?: error("没有导出内容"))
                        }
                        payload = null
                    }
            }
        val restore =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null)
                    vm.run("正在验证并恢复") {
                        val bytes =
                            context.contentResolver.openInputStream(uri)!!.use {
                                it.readBytesLimited(128_000_100)
                            }
                        val pass = password.toCharArray()
                        try {
                            BackupExport(vm.app).restore(bytes, pass)
                        } finally {
                            pass.fill('\u0000')
                            password = ""
                        }
                    }
            }
        val modelPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) vm.install(uri, hash)
            }
        val picker =
            rememberLauncherForActivityResult(
                ActivityResultContracts.PickMultipleVisualMedia(50)
            ) { uris ->
                if (uris.isNotEmpty()) vm.import(uris)
            }
        val files =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
                uris ->
                if (uris.isNotEmpty()) vm.import(uris)
            }
        var intro by remember { mutableStateOf(!prefs.getBoolean("onboarded", false)) }
        if (!unlocked) return@MaterialTheme
        if (intro)
            AlertDialog(
                onDismissRequest = {},
                title = { Text("把聊天记录，写成我们的故事。") },
                text = {
                    Text(
                        "欢迎来到拾言。截图与聊天仅保存在此设备；无需账号，不联网。\n\n导入 → 校对 → 收藏 → 故事。识别和 AI 可能出错，原文始终是事实依据。\n\n请及时创建加密备份，卸载会清除本地回忆。"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            prefs.edit().putBoolean("onboarded", true).apply()
                            intro = false
                        }
                    ) {
                        Text("开始拾起回忆")
                    }
                },
            )
        Scaffold(
            containerColor = colors.background,
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                if (section.isBlank()) listOf("拾言", "时间线", "回忆录", "我的")[tab]
                                else section,
                                fontWeight = FontWeight.Medium,
                            )
                            Text("BETWEEN US · 私人故事书", fontSize = 11.sp, letterSpacing = 2.sp)
                        }
                    },
                    navigationIcon = {
                        if (section.isNotBlank())
                            IconButton(onClick = { section = "" }) {
                                Icon(Icons.Outlined.ArrowBack, "返回")
                            }
                    },
                )
            },
            bottomBar = {
                NavigationBar {
                    listOf("首页", "时间线", "回忆录", "我的").forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i && section.isBlank(),
                            onClick = {
                                tab = i
                                section = ""
                            },
                            icon = {
                                Icon(
                                    listOf(
                                        Icons.Outlined.Home,
                                        Icons.Outlined.DateRange,
                                        Icons.Outlined.MenuBook,
                                        Icons.Outlined.Person,
                                    )[i],
                                    label,
                                )
                            },
                            label = { Text(label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                if (section.isBlank())
                    FloatingActionButton(onClick = { dialog = "添加" }) {
                        Icon(Icons.Outlined.Add, "添加回忆")
                    }
            },
        ) { padding ->
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(22.dp, 16.dp, 22.dp, 100.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (status.isNotBlank())
                    item {
                        Surface(
                            color = colors.secondaryContainer,
                            shape = MaterialTheme.shapes.medium,
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text(status)
                                if (busy) {
                                    LinearProgressIndicator(
                                        Modifier.fillMaxWidth().padding(top = 10.dp)
                                    )
                                    TextButton(onClick = { vm.cancel() }) { Text("取消当前操作") }
                                }
                            }
                        }
                    }
                when {
                    section == "AI 模型管理" -> {
                        item { Heading("一份留在手机里的灵感", "只使用 Qwen3-0.6B · CPU · 4096 tokens") }
                        item {
                            Text(
                                model?.let {
                                    "${it.name}\n${it.size/1_000_000} MB\n${it.status}\nSHA-256: ${it.sha256}"
                                } ?: "尚未安装模型。导入、校对、手写故事、基础报告和导出仍然可用。"
                            )
                        }
                        item {
                            Text(
                                "安装方法\n1. 使用浏览器访问 huggingface.co/litert-community/Qwen3-0.6B。\n2. 下载 Qwen3-0.6B.litertlm，并从官方文件详情复制 SHA-256。\n3. 在下方填写 SHA-256，选择本地文件。\n\n约需 586 MB 存储空间。导入后会校验哈希、尝试加载并执行真实推理。GPU 未经真机验证，未启用。"
                            )
                        }
                        item { Field(hash, { hash = it }, "官方文件 SHA-256") }
                        item {
                            Button(
                                onClick = { modelPicker.launch(arrayOf("*/*")) },
                                enabled = !busy,
                            ) {
                                Text("选择本地 .litertlm 文件")
                            }
                        }
                        if (model != null)
                            item {
                                OutlinedButton(
                                    onClick = { vm.run("正在删除模型") { vm.app.ai.remove() } }
                                ) {
                                    Text("删除模型")
                                }
                            }
                    }
                    section == "记忆管理" -> {
                        item { Heading("记忆有来处", "原文 → 分段 → 每日 → 每周 → 每月 → 年度") }
                        item {
                            Text(
                                "${messages.size} 条原文 · ${summaries.size} 份摘要 · ${events.size} 个事件\n${summaries.count{it.status=="待更新"}} 份摘要待更新\n所有 AI 内容均需要人工核实。未知日期单独整理，不伪造日期。"
                            )
                        }
                        item {
                            Field(start, { start = it }, "开始日期（可空，YYYY-MM-DD）")
                            Field(end, { end = it }, "结束日期（可空）")
                        }
                        item {
                            Button(onClick = { vm.process(start, end) }, enabled = !busy) {
                                Text("开始分层整理")
                            }
                            OutlinedButton(
                                onClick = {
                                    vm.annual(start.take(4).toIntOrNull() ?: LocalDate.now().year)
                                },
                                enabled = !busy,
                            ) {
                                Text("生成开始日期所在年的精选故事书")
                            }
                        }
                        items(tasks, key = { it.id }) { t ->
                            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("${t.status} · ${t.processed}/${t.total} · 批次 ${t.batch}")
                                    t.error?.let { Text(it, color = colors.error) }
                                    Row {
                                        TextButton(onClick = { vm.taskAction(t, "已暂停") }) {
                                            Text("暂停")
                                        }
                                        TextButton(onClick = { vm.taskAction(t, "排队") }) {
                                            Text("继续 / 重试")
                                        }
                                        TextButton(onClick = { vm.taskAction(t, "已取消") }) {
                                            Text("取消")
                                        }
                                    }
                                }
                            }
                        }
                        items(summaries.filter { it.level >= 0 }.take(100), key = { it.id }) { s ->
                            Column {
                                Text(
                                    "${listOf("分段","每日","每周","每月","年度")[s.level.coerceIn(0,4)]} · ${s.startDate ?: "日期待确认"} · ${s.status}",
                                    style = MaterialTheme.typography.labelLarge,
                                )
                                Text(s.text)
                                TextButton(
                                    onClick = {
                                        selected = s.sourceIds.split(',').toSet()
                                        onlySelected = true
                                        tab = 1
                                        section = ""
                                        query = ""
                                    }
                                ) {
                                    Text("查看关联原文")
                                }
                            }
                        }
                    }
                    section == "本地生成" -> {
                        item { Heading("让回忆慢慢成章", "生成结果是草稿，请对照原文审核") }
                        item {
                            Field(query, { query = it }, "查找话题，例如音乐（可空）")
                            Field(start, { start = it }, "开始日期（可空）")
                            Field(end, { end = it }, "结束日期（可空）")
                        }
                        item {
                            Text(
                                "依据已校对原文和相关摘要索引检索，每批生成短章节；不会将全年聊天一次塞入模型。当前每次检索最多 200 条原文。全年整理请先使用记忆管理。"
                            )
                        }
                        item {
                            FlowActions(listOf("聊天摘要", "重要事件", "短篇故事", "章节标题", "关键词")) {
                                vm.generate(query, it, start, end)
                            }
                        }
                        if (stream.isNotBlank()) item { Text(stream) }
                    }
                    section == "隐私与关于" -> {
                        item { Heading("属于你的，留在你这里。", "拾言 0.1.0-beta.2") }
                        item {
                            Text(
                                "无需账号、服务器、云端 AI、广告或分析 SDK。应用不声明 INTERNET 权限。\n\n聊天数据库使用 SQLCipher 加密，随机数据库密钥由 Android Keystore 包装。截图位于系统隔离的私有目录，未额外逐文件加密。\n\n备份使用 PBKDF2-HMAC-SHA256（210,000 次）与 AES-256-GCM。密码遗失无法恢复。模型不包含在备份中。\n\nOCR 和小模型可能出错，必须人工校对。应用锁依赖系统生物识别或锁屏凭据；不替代设备本身的安全。分享和导出文件离开私有目录后，由你负责保管。\n\n这是开发测试版，具体已验证范围见仓库测试报告。"
                            )
                        }
                    }
                    section == "备份与导出" -> {
                        item { Heading("好好保存，也能带走", "导出仅在你主动操作后进行") }
                        item {
                            Field(password, { password = it }, "备份密码（至少 8 位）", true)
                            Text("测试版单个加密备份上限 128 MB。恢复要求当前资料库为空；先妥善备份现有内容。模型不备份。")
                        }
                        item {
                            Button(
                                onClick = {
                                    vm.run("正在创建加密备份") {
                                        val pass = password.toCharArray()
                                        try {
                                            payload = BackupExport(vm.app).backup(pass)
                                        } finally {
                                            pass.fill('\u0000')
                                            password = ""
                                        }
                                        kotlinx.coroutines.withContext(
                                            kotlinx.coroutines.Dispatchers.Main
                                        ) {
                                            save.launch("拾言.shiyan")
                                        }
                                    }
                                },
                                enabled = !busy,
                            ) {
                                Text("导出加密备份")
                            }
                        }
                        item {
                            OutlinedButton(
                                onClick = { restore.launch(arrayOf("*/*")) },
                                enabled = !busy,
                            ) {
                                Text("选择备份并恢复")
                            }
                        }
                        item {
                            FlowActions(listOf("HTML 回忆录", "PDF 回忆录", "PNG 回忆卡片")) { kind ->
                                vm.run("正在生成导出文件") {
                                    val export = BackupExport(vm.app)
                                    payload =
                                        when (kind) {
                                            "HTML 回忆录" -> export.html(chapters, messages)
                                            "PDF 回忆录" -> export.pdf(chapters)
                                            else ->
                                                export.card(
                                                    "我们的片段",
                                                    messages
                                                        .filter { it.favorite }
                                                        .joinToString("\n\n") {
                                                            "${it.sender}：${it.text}"
                                                        }
                                                        .ifBlank { "请先在时间线收藏聊天内容。" },
                                                    AndroidColor.rgb(250, 241, 224),
                                                )
                                        }
                                    kotlinx.coroutines.withContext(
                                        kotlinx.coroutines.Dispatchers.Main
                                    ) {
                                        save.launch(
                                            if (kind.startsWith("HTML")) "拾言.html"
                                            else if (kind.startsWith("PDF")) "拾言.pdf" else "拾言.png"
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            OutlinedButton(onClick = { dialog = "清空" }) {
                                Text("删除全部本地数据", color = colors.error)
                            }
                        }
                    }
                    tab == 0 -> {
                        item {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "把日常，\n留成我们的故事。",
                                fontSize = 34.sp,
                                lineHeight = 46.sp,
                                fontWeight = FontWeight.Light,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "一张截图，一句晚安，\n那些平凡的瞬间，也值得珍藏。",
                                color = colors.onSurfaceVariant,
                                lineHeight = 26.sp,
                            )
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        picker.launch(
                                            PickVisualMediaRequest(
                                                ActivityResultContracts.PickVisualMedia.ImageOnly
                                            )
                                        )
                                    },
                                    enabled = !busy,
                                ) {
                                    Text("导入聊天截图")
                                }
                                OutlinedButton(onClick = { section = "本地生成" }) { Text("写成故事") }
                            }
                        }
                        item {
                            Heading(
                                "最近的篇章",
                                if (chapters.isEmpty()) "故事从你保存的第一句话开始。"
                                else "共 ${chapters.size} 篇，慢慢积攒。",
                            )
                        }
                        items(chapters.takeLast(3).reversed(), key = { it.id }) { c ->
                            ChapterCard(c) { chapterEdit = c }
                        }
                        item {
                            HorizontalDivider()
                            Heading("历史上的今天", "穿过时间，再遇见一次")
                        }
                        val today = LocalDate.now().toString().takeLast(5)
                        val onDay =
                            messages
                                .filter { it.confirmed && it.date?.takeLast(5) == today }
                                .take(3)
                        if (onDay.isEmpty()) item { Empty("今天的纸页仍然空白", "保存带日期的聊天，未来的今天就能再次读到。") }
                        items(onDay, key = { it.id }) {
                            Text("${it.date} · ${it.sender}\n${it.text}")
                        }
                        item { FlowActions(listOf("周报", "月报", "年度报告")) { vm.report(it) } }
                    }
                    tab == 1 -> {
                        item { Heading("沿着时间，重新遇见", "识别结果待校对；只有确认过的消息用于 AI") }
                        item {
                            Field(query, { query = it }, "搜索聊天、昵称或日期")
                            if (selected.isNotEmpty()) {
                                Text("已选 ${selected.size} 条关联原文")
                                FlowActions(listOf("取消选择", "保存事件")) {
                                    if (it == "取消选择") {
                                        selected = emptySet()
                                        onlySelected = false
                                    } else dialog = "事件"
                                }
                            }
                        }
                        val filtered =
                            messages.filter {
                                (query.isBlank() ||
                                    it.text.contains(query) ||
                                    it.sender.contains(query) ||
                                    it.date?.contains(query) == true) &&
                                    (!onlySelected || it.id in selected)
                            }
                        if (filtered.isEmpty())
                            item { Empty("还没有落在纸上的聊天", "导入截图，校对文字与发送者，再保存为真实的回忆。") }
                        items(filtered.take(500), key = { it.id }) { m ->
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                tonalElevation = if (m.confirmed) 0.dp else 2.dp,
                            ) {
                                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                                    Text(
                                        "${m.sender} · ${m.date ?: "日期待确认"} ${m.time.orEmpty()} · ${if(m.confirmed)"已校对" else "待校对"}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = colors.primary,
                                    )
                                    Text(
                                        m.text,
                                        Modifier.padding(vertical = 8.dp),
                                        lineHeight = 25.sp,
                                    )
                                    Row {
                                        TextButton(onClick = { edit = m }) { Text("校对") }
                                        TextButton(onClick = { vm.favorite(m) }) {
                                            Text(if (m.favorite) "取消收藏" else "收藏")
                                        }
                                        TextButton(onClick = { selected = selected + m.id }) {
                                            Text("选为依据")
                                        }
                                    }
                                }
                            }
                        }
                        if (filtered.size > 500) item { Text("当前显示前 500 条，请搜索日期或关键词缩小范围。") }
                    }
                    tab == 2 -> {
                        item { Heading("我们的故事书", "每一页都有来处，每一句都能重写") }
                        item {
                            FlowActions(listOf("新建章节", "本地生成", "查看事件")) {
                                when (it) {
                                    "新建章节" -> chapterEdit = StoryChapter(title = "", body = "")
                                    "本地生成" -> section = "本地生成"
                                    else -> dialog = "事件列表"
                                }
                            }
                        }
                        if (chapters.isEmpty())
                            item { Empty("封面之后，等你写下第一章", "手写一段心情，或让本地模型根据校对过的聊天起草。") }
                        items(chapters, key = { it.id }) { c -> ChapterCard(c) { chapterEdit = c } }
                    }
                    tab == 3 -> {
                        item { Heading("小小的私人档案馆", "所有回忆，由你掌控") }
                        item {
                            var left by remember {
                                mutableStateOf(prefs.getString("left", "对方").orEmpty())
                            }
                            var right by remember {
                                mutableStateOf(prefs.getString("right", "我").orEmpty())
                            }
                            Field(left, { left = it }, "左侧昵称")
                            Field(right, { right = it }, "右侧昵称")
                            TextButton(
                                onClick = {
                                    prefs
                                        .edit()
                                        .putString("left", left)
                                        .putString("right", right)
                                        .apply()
                                    vm.status.value = "昵称已保存；校对时可选择双方昵称"
                                }
                            ) {
                                Text("保存回忆空间昵称")
                            }
                        }
                        item {
                            var simplified by remember {
                                mutableStateOf(prefs.getBoolean("ocrSimplified", false))
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("将新识别结果整理为简体")
                                    Text(
                                        "可选文字转换，不代表 OCR 更准确；保留原始识别供校对。Android 10 及以上可用。",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                Switch(
                                    checked = simplified,
                                    enabled = ChineseScript.supported,
                                    onCheckedChange = {
                                        simplified = it
                                        prefs.edit().putBoolean("ocrSimplified", it).apply()
                                    },
                                )
                            }
                        }
                        items(listOf("AI 模型管理", "记忆管理", "备份与导出", "隐私与关于")) { s ->
                            ListItem(
                                headlineContent = { Text(s) },
                                trailingContent = { Icon(Icons.Outlined.ChevronRight, null) },
                                modifier = Modifier.clickable { section = s },
                            )
                        }
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("深色模式", Modifier.weight(1f))
                                Switch(
                                    checked = dark,
                                    onCheckedChange = {
                                        dark = it
                                        prefs.edit().putBoolean("dark", it).apply()
                                    },
                                )
                            }
                        }
                        item {
                            var lock by remember { mutableStateOf(prefs.getBoolean("lock", false)) }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("应用锁（系统生物识别 / 凭据）", Modifier.weight(1f))
                                Switch(
                                    checked = lock,
                                    onCheckedChange = { checked ->
                                        val auth =
                                            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                                                BiometricManager.Authenticators.DEVICE_CREDENTIAL
                                        if (
                                            !checked ||
                                                BiometricManager.from(context)
                                                    .canAuthenticate(auth) ==
                                                    BiometricManager.BIOMETRIC_SUCCESS
                                        ) {
                                            lock = checked
                                            prefs.edit().putBoolean("lock", checked).apply()
                                        } else vm.status.value = "请先在系统设置中配置锁屏凭据或生物识别"
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        if (dialog == "添加")
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("留住一个瞬间") },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                dialog = ""
                                picker.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly
                                    )
                                )
                            }
                        ) {
                            Text("从相册选择截图")
                        }
                        TextButton(
                            onClick = {
                                dialog = ""
                                files.launch(arrayOf("image/*"))
                            }
                        ) {
                            Text("从文件选择截图（SAF）")
                        }
                        TextButton(
                            onClick = {
                                dialog = ""
                                edit = Message(text = "")
                            }
                        ) {
                            Text("手动记录聊天")
                        }
                        TextButton(
                            onClick = {
                                dialog = ""
                                chapterEdit = StoryChapter(title = "", body = "")
                            }
                        ) {
                            Text("手写故事章节")
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = "" }) { Text("关闭") } },
            )
        edit?.let { m ->
            MessageDialog(
                m,
                assets[m.assetId],
                prefs.getString("left", "对方").orEmpty(),
                prefs.getString("right", "我").orEmpty(),
                onClose = { edit = null },
                onSave = { text, sender, date, time ->
                    if (messages.any { it.id == m.id }) vm.edit(m, text, sender, date, time)
                    else vm.add(text, sender, date)
                    edit = null
                },
                onAction = { a ->
                    when (a) {
                        "删除" -> vm.delete(m)
                        "上移" -> vm.move(m)
                        "合并下一条" -> vm.merge(m)
                        "按行拆分" -> vm.split(m)
                        "重新识别原图（保留已有消息）" -> vm.recognizeAgain(m)
                    }
                    edit = null
                },
            )
        }
        chapterEdit?.let { c ->
            var title by remember(c.id) { mutableStateOf(c.title) }
            var body by remember(c.id) { mutableStateOf(c.body) }
            AlertDialog(
                onDismissRequest = { chapterEdit = null },
                title = { Text("编辑故事章节") },
                text = {
                    LazyColumn {
                        item {
                            Field(title, { title = it }, "章节标题")
                            Field(body, { body = it }, "正文（引用格式：[消息ID]）")
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.chapter(c.id, title, body)
                            chapterEdit = null
                        }
                    ) {
                        Text("保存")
                    }
                },
                dismissButton = {
                    Row {
                        TextButton(
                            onClick = {
                                vm.run("正在删除章节") { vm.dao.deleteChapter(c.id) }
                                chapterEdit = null
                            }
                        ) {
                            Text("删除")
                        }
                        TextButton(onClick = { chapterEdit = null }) { Text("取消") }
                    }
                },
            )
        }
        if (dialog == "事件")
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("保存重要事件（待确认）") },
                text = { Field(eventTitle, { eventTitle = it }, "事件标题") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.event(eventTitle, "依据 ${selected.size} 条原文，内容请核实", selected.toList())
                            dialog = ""
                        }
                    ) {
                        Text("保存")
                    }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("取消") } },
            )
        if (dialog == "事件列表")
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("重要事件") },
                text = {
                    LazyColumn {
                        if (events.isEmpty()) item { Text("在时间线选择原文，保存可追溯的重要事件。") }
                        items(events, key = { it.id }) { e ->
                            Column {
                                Text(e.title)
                                Text(if (e.verified) "用户已确认" else "待核实")
                                Row {
                                    TextButton(
                                        onClick = {
                                            selected = e.messageIds.split(',').toSet()
                                            onlySelected = true
                                            tab = 1
                                            dialog = ""
                                        }
                                    ) {
                                        Text("原文")
                                    }
                                    TextButton(onClick = { vm.verify(e) }) { Text("确认") }
                                    TextButton(onClick = { vm.run("正在删除") { daoDelete(vm, e) } }) {
                                        Text("删除")
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { dialog = "" }) { Text("关闭") } },
            )
        if (dialog == "清空")
            AlertDialog(
                onDismissRequest = { dialog = "" },
                title = { Text("删除全部本地数据？") },
                text = { Text("聊天、截图、章节、摘要和模型都将删除，无法撤销。请确认已保存可恢复的备份。") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            vm.clearAll()
                            dialog = ""
                        }
                    ) {
                        Text("确认删除全部", color = colors.error)
                    }
                },
                dismissButton = { TextButton(onClick = { dialog = "" }) { Text("取消") } },
            )
    }
}

private suspend fun daoDelete(vm: AppViewModel, e: MemoryEvent) {
    vm.dao.deleteEvent(e.id)
}

@Composable
private fun Field(value: String, change: (String) -> Unit, label: String, secret: Boolean = false) {
    OutlinedTextField(
        value = value,
        onValueChange = change,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        visualTransformation =
            if (secret) PasswordVisualTransformation()
            else androidx.compose.ui.text.input.VisualTransformation.None,
    )
}

@Composable
private fun Heading(title: String, subtitle: String) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Light)
        Spacer(Modifier.height(7.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Empty(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 28.dp)) {
        Text("❦", fontSize = 36.sp, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        Text(body, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FlowActions(labels: List<String>, click: (String) -> Unit) {
    Column {
        labels.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { label -> OutlinedButton(onClick = { click(label) }) { Text(label) } }
            }
        }
    }
}

@Composable
private fun ChapterCard(c: StoryChapter, click: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 16.dp)) {
        Text(
            "故事 · ${if(c.generated)"AI 草稿，待审核" else "手写与整理"}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(9.dp))
        Text(c.title, style = MaterialTheme.typography.titleLarge)
        Text(
            c.body.take(150),
            Modifier.padding(top = 10.dp),
            lineHeight = 25.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(Modifier.padding(top = 20.dp))
    }
}

@Composable
private fun MessageDialog(
    m: Message,
    asset: String?,
    left: String,
    right: String,
    onClose: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
    onAction: (String) -> Unit,
) {
    var text by remember(m.id) { mutableStateOf(m.text) }
    var sender by remember(m.id) { mutableStateOf(m.sender) }
    var date by remember(m.id) { mutableStateOf(m.date.orEmpty()) }
    var time by remember(m.id) { mutableStateOf(m.time.orEmpty()) }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("对照截图校对原文") },
        text = {
            LazyColumn {
                item {
                    if (asset != null)
                        AsyncImage(
                            model = File(asset),
                            contentDescription = "原始聊天截图",
                            modifier = Modifier.fillMaxWidth().height(230.dp),
                        )
                    if (m.originalText != m.text)
                        Text(
                            "原始文字：${m.originalText}\n当前文字与原始结果不同，请对照截图或原始记录校对。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    Field(text, { text = it }, "聊天正文")
                    Field(sender, { sender = it }, "发送者")
                    Row {
                        TextButton(onClick = { sender = left }) { Text(left) }
                        TextButton(onClick = { sender = right }) { Text(right) }
                    }
                    Field(date, { date = it }, "日期（未知留空，YYYY-MM-DD）")
                    Field(time, { time = it }, "时间（未知留空，HH:mm）")
                    Text("消息 ID：${m.id}", style = MaterialTheme.typography.labelSmall)
                    FlowActions(
                        listOf("上移", "合并下一条", "按行拆分", "删除") +
                            if (asset != null) listOf("重新识别原图（保留已有消息）") else emptyList(),
                        onAction,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text, sender, date, time) }) { Text("确认保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val b = ByteArray(65536)
    while (true) {
        val n = read(b)
        if (n < 0) break
        require(out.size() + n <= limit) { "文件超过大小限制" }
        out.write(b, 0, n)
    }
    return out.toByteArray()
}
