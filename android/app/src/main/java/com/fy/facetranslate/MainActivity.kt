package com.fy.facetranslate

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.fy.facetranslate.data.Language
import com.fy.facetranslate.session.ConversationViewModel
import com.fy.facetranslate.session.Screen
import com.fy.facetranslate.ui.AppTheme
import com.fy.facetranslate.ui.HomeScreen
import com.fy.facetranslate.ui.SessionActions
import com.fy.facetranslate.ui.SessionScreen
import com.fy.facetranslate.ui.SettingsScreen
import com.fy.facetranslate.ui.SideActions

class MainActivity : ComponentActivity() {

    private val vm: ConversationViewModel by viewModels()

    /** Side that starts once the permission prompt is answered. */
    private var pendingFirst: Language? = null
    private var showVoiceNotice by mutableStateOf(false)

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        vm.setMicAllowed(granted)
        pendingFirst?.let { vm.startSession(it) }
        pendingFirst = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vm.setMicAllowed(hasMic())

        setContent {
            AppTheme {
                val ui = vm.ui
                when (ui.screen) {
                    Screen.HOME -> HomeScreen(
                        owner = ui.owner,
                        needsApiKey = ui.needsApiKey,
                        onSwap = vm::swapLanguages,
                        onStart = ::start,
                        onSettings = vm::openSettings,
                    )
                    Screen.SETTINGS -> {
                        BackHandler(onBack = vm::closeSettings)
                        SettingsScreen(vm.settings, vm::saveSettings, vm::closeSettings, vm::testConnection)
                    }
                    Screen.SESSION -> SessionScreen(ui, sessionActions())
                }

                if (showVoiceNotice) {
                    AlertDialog(
                        onDismissRequest = {},
                        title = { Text("语音需联网处理") },
                        text = {
                            Text("说话内容会实时发送到 Google Gemini 进行识别和翻译。本应用不在手机上保存录音和对话记录。接下来请允许使用麦克风；不允许也可以打字交流。")
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showVoiceNotice = false
                                getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_NOTICE_SHOWN, true).apply()
                                micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            }) { Text("知道了") }
                        },
                    )
                }
            }
        }
    }

    private fun sessionActions() = SessionActions(
        side = SideActions(
            onSpeak = vm::takeTurn,
            onPress = vm::pressToTalk,
            onRelease = vm::releaseToTalk,
            onType = vm::openKeyboard,
            onRetry = vm::retry,
            onResume = vm::resume,
            onZoom = vm::openZoom,
        ),
        onEnd = vm::endSession,
        onToggleFace = vm::toggleFaceToFace,
        onToggleHold = vm::toggleHoldMode,
        onReplay = vm::replay,
        onCloseZoom = vm::closeZoom,
        onSendTyped = vm::sendTyped,
        onCancelTyping = vm::cancelKeyboard,
        onRequestMic = ::requestMicAgain,
        onPauseForDialog = vm::pause,
        onResumeAfterDialog = vm::resume,
    )

    private fun start(first: Language) {
        if (hasMic()) {
            vm.setMicAllowed(true)
            vm.startSession(first)
            return
        }
        pendingFirst = first
        if (!getPreferences(MODE_PRIVATE).getBoolean(KEY_NOTICE_SHOWN, false)) {
            showVoiceNotice = true
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestMicAgain() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            // Permanently denied: the system dialog will not show again, so open app settings.
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
    }

    private fun hasMic() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onStart() {
        super.onStart()
        vm.setMicAllowed(hasMic())
    }

    override fun onStop() {
        super.onStop()
        // Lock screen, home, or incoming call UI: stop capture and playback until the user continues.
        if (!isChangingConfigurations) vm.pause()
    }

    private companion object {
        const val KEY_NOTICE_SHOWN = "voice_notice_shown"
    }
}
