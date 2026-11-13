# f2ft · 面对面翻译

面对面语音翻译的 Android App。旅行中，中文使用者和英语使用者共用一台手机：一方说话，手机识别后用另一方的语言把译文读出来，并在屏幕上显示双语字幕，播完自动轮到对方说。对方不需要安装应用，也不需要看懂中文界面。

语音识别、翻译和语音合成都由 Google Gemini Live API 实时完成。

## 功能

- **自动轮流对话**：按当前说话一侧的语言收音，句尾停顿后自动提交翻译；译文播完后自动轮到对方。
- **提前接话**：播报期间点对方的语言按钮，立即停止播报并切换说话方。
- **按住说话**：嘈杂环境下切换为按住说话、松手提交。
- **打字输入**：没有麦克风权限或不方便说话时，可以打字，走同样的翻译和播报流程。
- **面对面模式**：对方一侧的字幕和按钮整体旋转 180°，隔桌也能直接看。
- **放大与重播**：点译文放大显示，可重播最近的译文。
- **异常处理**：没听清时提示同侧重说；断网时保留原文，恢复后重试只更新同一条记录；切后台、锁屏或来电时停止收音和播报，回来后由用户点「继续」。
- **隐私**：对话记录只保存在内存中，结束对话即清除；不在手机上保存录音。

需求、交互原型和场景演示见 [`requirements/`](requirements/)。

## 模型

| 模型 ID | 说明 |
|---|---|
| `gemini-3.8-live`（默认） | 正式版，全双工语音到语音，延迟低，支持语音和文字输入 |
| `gemini-3.1-flash-live-preview` | 预览版 Live 模型 |
| `gemini-3.5-live-translate-preview` | 专用同传模型，只接受语音输入，打字和重试不可用；会持续翻译收到的声音，并自动判断语言，在轮流对话场景下效果不稳定 |
| `gemini-2.5-flash-native-audio-preview-12-2025` | 上一代原生音频模型 |

App 为中译英、英译中各保持一条 WebSocket 连接，当前轮到哪一方说，麦克风音频就发给对应的连接。

## 配置 API Key

API Key 不写在代码里，安装后在 App 内配置：

1. 在 [Google AI Studio](https://aistudio.google.com/) 创建 Gemini API Key。
2. 打开 App，点首页右上角「设置」。
3. 粘贴 API Key，选择模型和音色，可调整句尾停顿阈值（默认 800 毫秒）。
4. 点「测试连接」，显示「连接成功」后点「保存」。

Key 使用 Android Keystore 的 AES-GCM 密钥加密后保存在 App 私有存储中，并已排除系统备份。

## 构建与安装

环境要求：

- JDK 17
- Android SDK（compileSdk 36）
- 运行设备：Android 8.0（API 26）及以上，需要联网

```bash
cd android
./gradlew :app:assembleDebug    # 生成 app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug     # 安装到已通过 USB 调试连接的手机
```

首次构建前，在 `android/local.properties` 中写入 SDK 路径，例如：

```properties
sdk.dir=/Users/you/Library/Android/sdk
```

## 代码结构

```
android/app/src/main/java/com/fy/facetranslate/
├── MainActivity.kt                 页面切换、麦克风权限、前后台处理
├── audio/
│   ├── MicRecorder.kt              16 kHz PCM 采集，开启降噪和回声消除
│   └── PcmPlayer.kt                24 kHz PCM 流式播放
├── data/
│   ├── Language.kt                 中英两侧的语言代码和界面文案
│   └── SettingsStore.kt            设置读写与 API Key 加密
├── live/
│   └── GeminiLiveClient.kt         Gemini Live API WebSocket 协议
├── session/
│   ├── ConversationViewModel.kt    轮流对话状态机
│   └── Models.kt                   界面状态与对话记录
└── ui/                             Compose 界面：开始页、会话页、设置页
```

## 应用图标

图标由 `requirements/icon.png` 生成。换图后运行以下命令重新生成各分辨率资源（需要 Pillow）：

```bash
python3 .devtools/make_icons.py
```

## 已知限制

- 首版只支持普通话和英语，需要联网。
- 译文的文字和声音同时流式到达，无法严格做到先显示文字再播放声音。
- 端到端延迟、外放回录和嘈杂环境表现尚未做系统测试。

## License

[Apache License 2.0](LICENSE)
