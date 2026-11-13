package com.fy.facetranslate.ui

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object Palette {
    val Background = Color(0xFFFCFCF6)
    val Ink = Color(0xFF123E37)
    val Muted = Color(0xFF45685D)
    val Primary = Color(0xFF146552)
    val OwnPanel = Color(0xFFE0EFE6)
    val GuestPanel = Color(0xFFE4EDF1)
    val TurnBorder = Color(0xFF43806B)
    val Line = Color(0xFFBAD0C6)
    val Warn = Color(0xFF9A5B00)
    val Error = Color(0xFFB3261E)
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Primary,
            onPrimary = Color.White,
            background = Palette.Background,
            surface = Palette.Background,
            onBackground = Palette.Ink,
            onSurface = Palette.Ink,
        ),
        content = content,
    )
}

/** Touch targets are at least 48 dp and always carry a text label. */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Palette.Primary),
    ) { Text(text, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
        shape = RoundedCornerShape(12.dp),
    ) { Text(text, color = Palette.Primary) }
}
