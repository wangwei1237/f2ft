package com.fy.facetranslate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fy.facetranslate.data.Language

@Composable
fun HomeScreen(
    owner: Language,
    needsApiKey: Boolean,
    onSwap: () -> Unit,
    onStart: (first: Language) -> Unit,
    onSettings: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Palette.Background).safeDrawingPadding().padding(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("面对面翻译", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
            SecondaryButton("设置", onSettings)
        }
        Spacer(Modifier.height(48.dp))
        Text("和对方说句话", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Text("把手机放在两人之间。", color = Palette.Muted)
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier.fillMaxWidth().background(Palette.OwnPanel, RoundedCornerShape(18.dp)).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("我的语言", color = Palette.Muted, fontSize = 13.sp)
            Text(owner.label, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            SecondaryButton("交换语言 ⇅", onSwap)
            Text("对方的语言　${owner.other().label}")
        }
        Spacer(Modifier.height(20.dp))
        if (needsApiKey) {
            Text("尚未配置 Gemini API Key，请先到设置中填写。", color = Palette.Warn)
            Spacer(Modifier.height(8.dp))
        }
        PrimaryButton("开始对话", { onStart(owner) }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        SecondaryButton("让对方先说 · ${owner.other().speakButton}", { onStart(owner.other()) }, Modifier.fillMaxWidth())
        Spacer(Modifier.weight(1f))
        Text(
            "语音和文字会联网发送至 Google Gemini 处理。本应用不在手机上保存录音或对话记录，结束对话即清除。",
            color = Palette.Muted, fontSize = 12.sp,
        )
    }
}
