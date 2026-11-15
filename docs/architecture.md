# 开发架构与构建约束

本文档描述 f2ft 的代码结构、运行机制、编码约定和构建验证方法。修改代码前先阅读相关章节；改动涉及这里描述的结构或约束时，同步更新本文档。

## 1. 项目与目录

f2ft 是面对面语音翻译 Android App：中文使用者和英语使用者共用一台手机轮流说话，Google Gemini Live API 通过全双工 WebSocket 实时完成识别、翻译和朗读。产品行为以 `requirements/产品需求.md` 为准。

- `requirements/`：产品需求、交互原型、演示视频、图标原图，以及按日期编号的需求文档（见 `docs/development-process.md`）。
- `docs/`：开发流程（`development-process.md`）和本文档。
- `android/`：Gradle 工程，单模块 `:app`，包名 `com.fy.facetranslate`。
- `.devtools/make_icons.py`：由 `requirements/icon.png` 生成启动图标（需要 Pillow）。`.devtools/` 下其他文件是本地调试脚本，已被 git 忽略。

## 2. 代码结构

```
android/app/src/main/java/com/fy/facetranslate/
├── MainActivity.kt                 页面切换、麦克风权限、前后台处理
├── audio/
│   ├── MicRecorder.kt              16 kHz 单声道 PCM 采集，约 100 ms 一块，开启降噪和回声消除
│   └── PcmPlayer.kt                24 kHz PCM 流式播放
├── data/
│   ├── Language.kt                 中英两侧的语言代码和各自语言的界面文案
│   └── SettingsStore.kt            设置读写，API Key 用 Android Keystore 加密
├── live/
│   └── GeminiLiveClient.kt         Live API 协议：setup 消息、音频/文字输入、服务端消息解析
├── session/
│   ├── ConversationViewModel.kt    轮流对话状态机、连接管理、测试连接、音色试听
│   └── Models.kt                   UiState、Phase、Record
└── ui/                             Compose 界面：开始页、会话页、设置页、主题
```

## 3. 运行机制

### 连接

- **翻译播报连接**：中译英、英译中各一条，开始对话时建立。轮到哪一方说，麦克风音频就发给哪一条。翻译行为由 `systemInstruction` 中的口译提示词约束。
- **实时字幕连接**：开启实时字幕时，中文、英文两侧各一条，使用转写模型（默认 `gemini-3.5-transcribe-live`，只返回文字）。说话时，同一份麦克风音频同时发给翻译播报连接和字幕连接。
  - 字幕连接返回的 `interimInputTranscription` 只用于浅灰色实时显示，不参与翻译，也不写入对话记录。
  - 翻译播报模型返回的 `inputTranscription` 是最终原文，到达后替换实时字幕。
  - 字幕连接的错误不显示给用户；断开后在下一轮开始时重连。

### 状态与轮次

- 所有界面状态变化都在主线程进行；WebSocket 和麦克风回调先切回主线程再修改状态。
- 被取消的轮次返回的迟到结果，绝不能更新更新的轮次。ViewModel 会核对回调来自当前轮次的连接；轮次在中途被打断时，关闭并重建该方向的连接（翻译和字幕都一样）。修改轮次逻辑时必须保持这一点。
- 播报和重播期间不收音；正常播报结束才交给对方，重播不改变轮次。

## 4. Gemini Live API 约束

- 端点：`wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=...`
- 音频输入：16-bit PCM、16 kHz、单声道、`audio/pcm;rate=16000`，约 100 ms 一块。音频输出：24 kHz PCM。
- 默认翻译播报模型 `gemini-3.8-live`：只在一句话结束后返回 `inputTranscription`，没有实时识别结果。
- `gemini-3.5-transcribe-live`：只输出文字（`responseModalities: ["TEXT"]`），通过 `inputAudioTranscription.languageCodes` 指定识别语言；说话过程中持续返回 `interimInputTranscription`（每次是整句的当前假设），句末返回 `inputTranscription`。
- `gemini-3.5-live-translate-preview` 的配置格式与其他模型不同，且官方文档与实际不符：
  - `translationConfig` 必须放在 `generationConfig` 内（放在顶层会被拒绝，返回 1007）。
  - `inputAudioTranscription` / `outputAudioTranscription` 必须放在 `setup` 顶层（放在 `generationConfig` 内会被拒绝）。
  - 目标语言代码取自其支持列表（`zh-Hans`、`en`），存放在 `Language.translationCode`。
  - 不支持系统指令、文字输入和音色选择。
- 服务端先校验 setup 格式、再校验 API Key，所以可以用无效 Key 检查配置格式：格式正确时返回 `1007 API key not valid`。
- 模型 ID 和字段名变化频繁，修改前先查最新官方文档。

## 5. 编码约定

- 使用 Kotlin 和 Jetpack Compose，不使用依赖注入框架。除 `app/build.gradle.kts` 中已有的库外，非必要不新增依赖。
- 与周围代码保持一致的命名和注释密度。
- 每一侧的界面文案放在 `Language` 中，并用该侧自己的语言显示；手机主人使用的页面（开始页、设置页）用中文。
- 触控区域至少 48 dp，按钮带文字标签。
- 图标用矢量资源（`res/drawable/`），不引入图标库。

## 6. 构建与验证

### 构建

必须使用 JDK 17（本机默认 JDK 可能更新，构建不支持）：

```bash
cd android
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew :app:assembleDebug
JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home ./gradlew :app:installDebug
```

- `android/local.properties` 存放 `sdk.dir`，不提交到仓库。
- Gradle 和依赖下载需要联网。
- 构建必须没有编译错误；新增代码不应引入编译警告。

### 真机验证

- 目前没有单元测试，改动通过"构建 → 安装到真机 → 操作受影响的流程 → 截图"来验证，测试报告要求见 `docs/development-process.md`。
- `adb devices` 列表为空时，`adb kill-server && adb start-server` 通常可以恢复。`installDebug` 报 "No connected devices" 时，可以改用 `adb -s <设备号> install -r app/build/outputs/apk/debug/app-debug.apk`。
- 测试设备（华为 ALN-AL80）没有 `screenrecord`，也没有 Google 服务和可用的系统语音识别。需要连续画面时，在手机上循环执行 `screencap`。
- 需要语音输入时，可以用电脑扬声器朗读测试句（中文 `say -v Tingting`，英文 `say -v Samantha`）。测试环境里的其他声音也会被识别，记录结果时要区分。
- 无法在真机上验证的内容，要明确说明，不能当作已通过。
