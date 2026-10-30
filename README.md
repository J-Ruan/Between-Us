# 拾言 · Between Us

把聊天记录，写成我们的故事。

An offline-first native Android app for private chat memories, bundled Chinese OCR, hierarchical memory and on-device AI.

**状态：0.1.0-beta.2 开发测试版。并非任务书全部功能已验收的正式版本。** 实际运行结果见 [测试报告](docs/TESTING.md)，功能边界见 [测试版说明](docs/RELEASE.md)。没有使用演示聊天或预设文字冒充 AI。

## 已接入的功能

- Kotlin / Compose / Material 3 四页导航：首页、时间线、回忆录、我的；温暖浅色与深色主题。
- Photo Picker 与 SAF 批量选图、图片 SHA-256 重复检测、原图保留、串行中文 OCR（模型随 APK 安装）。beta.2 修复长截图被整图缩小的问题，使用短段解码、细字放大与对比度预处理。
- 可选简体整理（默认关闭，Android 10+），保留未经转换的原始 OCR；已保存的截图可以重新识别，旧消息不覆盖。详见 [OCR 修复与回归](docs/TESTING-OCR.md)。
- OCR 左右位置与日期识别、原图对照、文字／发送者／时间校对、上移、合并、按行拆分和删除。识别结果默认未确认；相邻重复仅作为提示，不自动删除。
- Room + SQLCipher 数据库、原始文字与编辑修订、消息 ID 和故事引用、用户确认事件、收藏与搜索。
- Qwen3-0.6B 的 LiteRT-LM CPU 引擎：SAF 侧载、SHA-256 校验、加载与推理检查、流式生成、取消、删除模型。没有模型时保留其他功能和明确标注的基础报告模板。
- 分层整理：可恢复 WorkManager 任务、固定原文快照、分段／日／周／月／年摘要、递归合并、完整来源 ID 与依赖、原文变化后失效标记。年度精选按用户确认事件和收藏原文逐章生成。
- 手写／编辑章节、关键词原文与摘要检索、周／月／年度基础报告、PDF、离线 HTML、PNG 精选聊天卡片。
- Keystore 包装数据库密钥、应用锁、关闭系统自动备份、密码加密备份与恢复。

## 安装 APK

仓库地址：<https://github.com/J-Ruan/Between-Us>。

已发布 [v0.1.0-beta.1 预发布版](https://github.com/J-Ruan/Between-Us/releases/tag/v0.1.0-beta.1)：[下载 app-debug.apk](https://github.com/J-Ruan/Between-Us/releases/download/v0.1.0-beta.1/app-debug.apk)（约 99.4 MiB）与 [SHA256SUMS.txt](https://github.com/J-Ruan/Between-Us/releases/download/v0.1.0-beta.1/SHA256SUMS.txt)。[发布构建](https://github.com/J-Ruan/Between-Us/actions/runs/41015048930) 已成功；发布制品已重新下载并校验。也可从成功的 Actions 构建下载 `shiyan-debug-apk`。本地构建产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

Android 8.0+，arm64-v8a / x86_64。重点目标荣耀 90；尚需在该设备验收性能、发热与本地模型兼容性。允许系统“安装未知应用”后打开 APK。它使用 Debug 签名，不是正式商店签名；不同机器生成的 Debug 密钥可能不同，无法直接覆盖安装。需要卸载时务必先备份，并确认密码可恢复。

## 第一次使用

1. 导入聊天截图；相册不兼容时选择“从文件选择截图”。
2. 在时间线逐条对照原图，校对文字、左右发送者、日期与时间。未知日期留空。点击“确认保存”后才参与 AI 整理。
3. 收藏片段，或选多条原文保存重要事件；事件需对照原文后点击确认。
4. 可直接手写章节、生成基础报告和导出；AI 是可选的本地功能。
5. 长历史先在“我的 → 记忆管理”选择日期范围，完成分层整理，再生成年度精选。任务暂停后可继续；原文变更会要求按受影响日期新建整理任务。
6. 定期在“备份与导出”创建加密备份。恢复要求空资料库，防止意外覆盖。

## 安装本地模型

仅支持 [litert-community/Qwen3-0.6B](https://huggingface.co/litert-community/Qwen3-0.6B) 的 `Qwen3-0.6B.litertlm`。在外部浏览器提前下载（约 586 MB，以官方文件实际大小为准），从文件详情复制完整 SHA-256。

打开“我的 → AI 模型管理”，填写 SHA-256，再选择本地 `.litertlm` 文件。应用复制到 noBackup 私有目录，检查大小（400–900 MB）、哈希，再通过原生加载器验证容器并执行短中文推理。只有这些步骤成功后才记录已安装状态。文件后缀和大小本身不证明兼容性，官方哈希也需要用户从可信页面获取。安装后无需网络。模型不打包、不提交 Git，也不进入回忆备份。

固定引擎 `com.google.ai.edge.litertlm:litertlm-android:0.16.1`。依据 [该版本官方 Kotlin 源码](https://github.com/google-ai-edge/LiteRT-LM/tree/v0.16.1/kotlin/java/com/google/ai/edge/litertlm) 使用 Engine / Conversation 流接口。CPU 4 线程、4096 总上下文；GPU 未启用。模型真机加载、实际中文质量、长时间性能尚未验收，不能因集成了 API 就视为已通过。

## 分层记忆与数据结构

见 [架构](docs/ARCHITECTURE.md)。原文不会被摘要覆盖。没有直接可用的匹配 Tokenizer 计数接口时，使用 Qwen 字节 BPE 的 UTF-8 字节数作为保守上界，**并非按汉字数估算**；输入批次最多 2200 字节上界，为提示、输出 640 tokens 与模板保留空间。超长单条按 Unicode 码点切分，所有分片保留原消息 ID。

所有生成内容均为待审核材料。ID 校验能拦截不存在的引用，但不能自动证明自然语言里的每一项事实；最终需要用户对照原文确认。模型不会自动将推测标记为正式事件。

## 开发与编译

需要完整 JDK 21（含 javac）、Android SDK 35、Build Tools 35.0.0、受信任的 Maven 网络访问。Gradle Wrapper 8.13 带发行包 SHA-256；AGP 8.10.1、Kotlin 2.2.20、Room 2.7.2、Compose BOM 2025.05.01 等固定于构建文件。源码分 `app`（分层 Android 实现）与 `core`（独立 JVM 数据处理／密码学）。首次构建会从 Google Maven / Maven Central 下载依赖。

```sh
# local.properties 写入你的 Android SDK 路径；不要提交该文件
./gradlew :core:test testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
python3 scripts/check_apk.py
# 专用测试模拟器或测试手机；测试会创建和清除测试资料库，勿用私人数据设备
./gradlew connectedDebugAndroidTest
```

`testDebugUnitTest` 的 Android 模块没有 JVM 测试；真正执行的 34 项 JVM 测试位于 `:core:test`。设备测试独立验证数据库、真实中文 OCR、备份恢复、导出和启动，结果不能由零测试任务代替。

## 常见问题

- **安装后无模型**：正常，模型单独安装；导入、校对、手写与基础报告照常可用。
- **AI 失败**：检查官方文件哈希、存储空间、设备内存与 CPU 兼容性；错误会展示，不会切换云端服务。
- **摘要提示待更新**：原文变更使相关摘要失效；按受影响日期重新创建整理任务。无关日期不必重算。
- **截图日期不完整**：不猜测年月，人工补充；未知日期单独保留。
- **备份失败**：当前采用内存认证加密，压缩包上限 128 MB、解压总量上限 256 MB；并不适合大型图片库。请勿把此测试版作为唯一数据副本。
- **不能截屏**：应用为保护聊天设置了 FLAG_SECURE。暂未提交运行截图，绝不使用 UI 渲染图冒充实际截图。

## 隐私与许可

见 [隐私政策](PRIVACY.md)。数据库加密不代表截图逐文件加密；截图依赖 Android 私有目录与设备文件加密。PDF / HTML / PNG 导出是明文，请自行保管。备份密码不可找回。

源码采用 [MIT](LICENSE)。模型和第三方依赖遵循各自许可，发布前需同时核对模型仓库与依赖许可。本仓库不分发模型权重。
