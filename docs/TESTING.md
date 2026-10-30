# 实际验证报告

日期：2026-10-30。运行环境：Linux x86_64 云机器、JDK 21.0.9、Android SDK 35、Android 11 (API 30) x86_64 软件模拟器。不是荣耀 90 真机。

| 检查 | 结果 |
|---|---|
| `:core:test` | 26 通过，0 失败，0 跳过 |
| `testDebugUnitTest` | Android 模块 NO-SOURCE；不计入测试通过数量 |
| `connectedDebugAndroidTest` | 6 通过，0 失败，0 跳过 |
| `lintDebug` | 通过；0 Error，7 个 Warning（依赖新版本提示与 KTX 风格建议等） |
| `assembleDebug` | 通过，产生真实 Debug 签名 APK |
| `assembleDebugAndroidTest` | 通过 |
| `scripts/check_apk.py` | 通过，完整 ZIP / DEX / 原生库 / Bundled 中文 OCR 权重 / 合并 Manifest |
| `scripts/cloud-setup.sh` | 在正确工作目录实际执行成功；Wrapper 发行包校验成功 |

## 执行的测试

JVM：100／1000／10000 条消息分批与顺序、UTF-8 Token 上界、完整上下文预留、空批次与准确边界、超长单条 Unicode 无丢失、缺失与无效日期、日期时间提取、左右／中心位置、垂直排序、连续重叠建议、不误删短重复、发送者和时间约束、引用 ID 白名单、HTML 转义、加密备份往返、随机盐与 IV、错误密码、篡改、密码长度和格式版本。

Android：

1. 10000 条消息实际写入 Room，并按页读取无丢失。
2. CRUD、故事引用、消息编辑使摘要失效、原始文字保留、删除引用级联。
3. 检查点保存和稳定批次 ID 重复写入的幂等性。
4. Bundled ML Kit 对 Canvas 绘制的中文测试夹具执行真实 OCR，识别“音乐”并返回文字坐标。
5. SQLCipher 数据库、截图资源与摘要依赖备份恢复，PDF / HTML / PNG 实际生成，数据库文件无明文 SQLite header。
6. 无模型启动实际 Activity，确认 INTERNET 权限被拒绝。

原始 JUnit 结果保存在 [JVM XML](test-results/jvm.xml) 与 [Android XML](test-results/android.xml)。首次设备测试因一个测试方法返回 Boolean 导致 JUnit 初始化失败，已修正为 Unit；上表为最新完整重跑结果。

## APK

大小：104,195,632 字节。SHA-256：

`9ecbbd0212daf395240cff3a5175df85d6426d942b152ae6d563b9bf2a04fbee`

APK 内不包含 `.litertlm` / `.gguf` 模型权重。合并 Manifest 没有 INTERNET、ACCESS_NETWORK_STATE、READ_EXTERNAL_STORAGE、WRITE_EXTERNAL_STORAGE 权限，关闭系统自动备份。内置中文 OCR 权重已实际识别验证。

## 未执行／尚未验收

没有导入 Qwen3-0.6B 文件，因此实际模型加载、流式中文生成、摘要质量、多级 AI 合并、年度章节质量、推理取消和长时间模型任务恢复**未通过实测**；不能用测试夹具摘要代替真实模型效果。设备测试验证了数据库与检查点机制，未模拟系统杀进程后的完整模型恢复。

未在荣耀 90 验收 CPU 性能、发热、内存、系统省电策略、真实微信多种截图布局、Biometric 系统交互与大型图库备份峰值。模型默认 CPU；没有启用未经验证的 GPU。未执行所有 Compose 页面交互的自动验收，也未提供虚假运行截图。

发布状态以实际 GitHub 操作结果为准，工作流文件存在不代表远端 Actions 或 Release 已成功。
