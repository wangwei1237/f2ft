package com.fy.facetranslate.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fy.facetranslate.data.UpdateChecker
import com.fy.facetranslate.data.UpdateResult

/** Installed build, read from the package manager. */
data class AppInfo(val versionName: String, val versionCode: Long, val abi: String)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AboutSection(
    info: AppInfo,
    onCheckUpdate: (String, (UpdateResult) -> Unit) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateResult?>(null) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("关于", fontWeight = FontWeight.SemiBold)
        Text("当前版本：${info.versionName}（${info.versionCode}）")
        Text("手机架构：${info.abi}")
        Text(
            UpdateChecker.PROJECT_URL,
            color = Palette.Primary,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier.heightIn(min = 48.dp).padding(vertical = 12.dp)
                .clickable { onOpenUrl(UpdateChecker.PROJECT_URL) },
        )
        SecondaryButton(
            if (checking) "正在检查…" else "检查更新",
            {
                checking = true
                result = null
                onCheckUpdate(info.versionName) { r ->
                    checking = false
                    result = r
                }
            },
            Modifier.fillMaxWidth(),
            enabled = !checking,
        )

        when (val r = result) {
            null -> Unit
            is UpdateResult.UpToDate -> Text("已是最新版本", color = Palette.Primary)
            is UpdateResult.Failed -> Text("检查失败：${r.reason}", color = Palette.Error)
            is UpdateResult.DevBuild -> {
                Text("当前为开发版本，最新发布版为 v${r.latest.version}", color = Palette.Warn)
                SecondaryButton("查看发布页", { onOpenUrl(r.latest.pageUrl) })
            }
            is UpdateResult.Available -> {
                Text("发现新版本 v${r.latest.version}", color = Palette.Primary, fontWeight = FontWeight.SemiBold)
                if (r.latest.summary.isNotBlank()) Text(r.latest.summary, color = Palette.Muted, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    r.latest.apkUrl?.let { url -> PrimaryButton("下载更新", { onOpenUrl(url) }) }
                    SecondaryButton("查看发布页", { onOpenUrl(r.latest.pageUrl) })
                }
                Text("下载完成后，点击下载的 APK 安装；系统提示时允许安装未知来源应用。",
                    color = Palette.Muted, fontSize = 12.sp)
            }
        }
    }
}
