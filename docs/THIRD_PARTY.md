# 第三方组件

项目源码使用 MIT；以下组件并不因此改变原有许可。APK 由 Gradle 从官方仓库解析，构建文件固定版本。

| 组件 | 用途 | 上游 |
|---|---|---|
| Kotlin / Coroutines | 语言与异步 | https://github.com/JetBrains/kotlin |
| AndroidX Compose / Room / WorkManager / Biometric | UI、数据库与任务 | https://source.android.com/ |
| Google ML Kit Chinese Bundled | 离线中文 OCR | https://developers.google.com/ml-kit/terms |
| Google LiteRT-LM | 本地推理运行时 | https://github.com/google-ai-edge/LiteRT-LM |
| SQLCipher Android | 数据库加密 | https://github.com/sqlcipher/sqlcipher-android |
| Coil | 本地图片显示 | https://github.com/coil-kt/coil |
| Gson | 运行时传递依赖兼容 | https://github.com/google/gson |
| Qwen3-0.6B LiteRT community conversion | 用户自行侧载，不在源码或 APK 分发 | https://huggingface.co/litert-community/Qwen3-0.6B |

模型需由用户从官方仓库下载并遵循该仓库的实际许可；本应用仅提供本地加载接口。不使用云端 AI API，也不向这些上游站点发送聊天内容。
