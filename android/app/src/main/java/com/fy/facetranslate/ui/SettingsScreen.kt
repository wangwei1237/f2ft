package com.fy.facetranslate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fy.facetranslate.data.AppSettings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    initial: AppSettings,
    onSave: (AppSettings) -> Unit,
    onCancel: () -> Unit,
    onTest: (AppSettings, (String?) -> Unit) -> Unit,
) {
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var showKey by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(initial.model) }
    var voice by remember { mutableStateOf(initial.voice) }
    var silence by remember { mutableStateOf(initial.silenceMs.toString()) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    fun current() = initial.copy(
        apiKey = apiKey.trim(),
        model = model.trim(),
        voice = voice.trim(),
        silenceMs = silence.toIntOrNull()?.coerceIn(300, 3000) ?: AppSettings.DEFAULT_SILENCE_MS,
    )

    Column(
        Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("模型设置", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            SecondaryButton("返回", onCancel)
        }

        Text("Gemini API Key", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it; testResult = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("在 Google AI Studio 创建的 API Key") },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        SecondaryButton(if (showKey) "隐藏" else "显示", { showKey = !showKey })
        Text("Key 使用 Android Keystore 加密后仅保存在本机，不会备份。", color = Palette.Muted, fontSize = 12.sp)

        Text("Live API 模型", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.PRESET_MODELS.forEach { id ->
                FilterChip(selected = model == id, onClick = { model = id; testResult = null }, label = { Text(id) })
            }
        }
        OutlinedTextField(
            value = model,
            onValueChange = { model = it; testResult = null },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("模型 ID（可自定义）") },
        )
        Text(
            "默认 gemini-3.8-live：全双工语音到语音、低延迟，支持语音和文字输入。" +
                "gemini-3.5-live-translate-preview 为专用同传模型，只接受语音输入，打字和重试可能不可用。",
            color = Palette.Muted, fontSize = 12.sp,
        )

        Text("播报音色", fontWeight = FontWeight.SemiBold)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AppSettings.PRESET_VOICES.forEach { v ->
                FilterChip(selected = voice == v, onClick = { voice = v }, label = { Text(v) })
            }
        }

        Text("句尾停顿阈值（毫秒）", fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = silence,
            onValueChange = { silence = it.filter(Char::isDigit).take(4) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Text("自动模式下说话人停顿多久算一句话结束。数值越大越不容易被提前截断，但等待更久。范围 300–3000。",
            color = Palette.Muted, fontSize = 12.sp)

        SecondaryButton(
            if (testing) "正在测试…" else "测试连接",
            {
                testing = true
                testResult = null
                onTest(current()) { error ->
                    testing = false
                    testResult = error?.let { "连接失败：$it" } ?: "连接成功"
                }
            },
            Modifier.fillMaxWidth(),
            enabled = !testing && apiKey.isNotBlank() && model.isNotBlank(),
        )
        testResult?.let {
            Text(it, color = if (it == "连接成功") Palette.Primary else Palette.Error)
        }
        PrimaryButton("保存", { onSave(current()) }, Modifier.fillMaxWidth())
    }
}
