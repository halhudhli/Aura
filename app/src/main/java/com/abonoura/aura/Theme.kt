package com.abonoura.aura

import android.graphics.BitmapFactory
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.File

/** The looks the user can switch between in the settings. */
enum class AuraLook(val label: String) {
    Glass("زجاجي"),
    MawjDark("موج الداكن"),
    PureBlack("أسود خالص"),
    Light("نهاري فاتح"),
    Sea("بحري"),
}

data class AuraColors(
    val background: Brush,
    val text: Color,
    val subText: Color,
    val card: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val isLight: Boolean,
    /** True when a picture (the user's, or the default gradient) sits behind the screens. */
    val pictureBackground: Boolean,
)

private fun solid(color: Color) = Brush.verticalGradient(listOf(color, color))

fun colorsFor(look: AuraLook): AuraColors = when (look) {
    AuraLook.Glass -> AuraColors(
        // The wave identity: midnight, turquoise light and a violet edge.
        background = Brush.linearGradient(listOf(Color(0xFF080B15), Color(0xFF111C36), Color(0xFF24205A))),
        text = Color(0xFFF7F7FF), subText = Color(0xFFC4D8EE).copy(alpha = 0.80f),
        card = Color(0xFF080B15).copy(alpha = 0.62f), line = Color(0xFF42E8D5).copy(alpha = 0.22f),
        accent = Color(0xFF42E8D5), onAccent = Color(0xFF041923), isLight = false, pictureBackground = true,
    )
    AuraLook.MawjDark -> AuraColors(
        background = solid(Color(0xFF080B15)),
        text = Color(0xFFF7F7FF), subText = Color(0xFFA6B6CB),
        card = Color(0xFF10192B), line = Color(0xFF8B5CF6).copy(alpha = 0.18f),
        accent = Color(0xFFB69BFF), onAccent = Color(0xFF170B33), isLight = false, pictureBackground = false,
    )
    AuraLook.PureBlack -> AuraColors(
        background = solid(Color.Black),
        text = Color.White, subText = Color(0xFF8A8A8A),
        card = Color(0xFF0C0C0C), line = Color(0xFF222222),
        accent = Color(0xFF42E8D5), onAccent = Color(0xFF041923), isLight = false, pictureBackground = false,
    )
    AuraLook.Light -> AuraColors(
        background = solid(Color(0xFFF4F2EE)),
        text = Color(0xFF1B1B1B), subText = Color(0xFF6A6A6A),
        card = Color.White, line = Color(0xFFE2DED6),
        accent = Color(0xFF006675), onAccent = Color.White, isLight = true, pictureBackground = false,
    )
    AuraLook.Sea -> AuraColors(
        background = Brush.linearGradient(listOf(Color(0xFF080B15), Color(0xFF073C51), Color(0xFF20204A))),
        text = Color.White, subText = Color.White.copy(alpha = 0.72f),
        card = Color.White.copy(alpha = 0.09f), line = Color.White.copy(alpha = 0.14f),
        accent = Color(0xFF42E8D5), onAccent = Color(0xFF041923), isLight = false, pictureBackground = true,
    )
}

val LocalAura = staticCompositionLocalOf { colorsFor(AuraLook.Glass) }

val CardShape = RoundedCornerShape(16.dp)

/** A card in the current look: translucent over a picture, solid otherwise. */
@Composable
fun Modifier.auraCard(shape: Shape = CardShape): Modifier {
    val c = LocalAura.current
    return this.clip(shape).background(if(Prefs.look==AuraLook.Glass)c.card.copy(alpha=Prefs.glassOpacity/100f)else c.card).border(1.dp, c.line, shape)
}

/** Applies the look and draws its background once, behind every screen. */
@Composable
fun AuraTheme(motionEnabled: Boolean = true, content: @Composable () -> Unit) {
    val look = Prefs.look
    val colors = remember(look) { colorsFor(look) }
    val scheme = if (colors.isLight) {
        lightColorScheme(primary = colors.accent, onPrimary = colors.onAccent, secondary = Color(0xFF7242D0), surface = Color.White, onSurface = colors.text)
    } else {
        darkColorScheme(primary = colors.accent, onPrimary = colors.onAccent, secondary = Color(0xFFB69BFF), surface = Color(0xFF10192B), onSurface = colors.text)
    }
    CompositionLocalProvider(LocalAura provides colors) {
        MaterialTheme(colorScheme = scheme) {
            Box(Modifier.fillMaxSize().background(colors.background)) {
                if (look == AuraLook.Glass) WallpaperBackground(motionEnabled)
                content()
            }
        }
    }
}

/** The user's own picture behind the glass look: softly blurred and dimmed. */
@Composable
private fun GlassPicture() {
    val context = LocalContext.current
    val name = Prefs.backgroundFile ?: return
    val bitmap = remember(name) {
        runCatching {
            val file = File(context.filesDir, name)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 1280 || bounds.outHeight / (sample * 2) >= 1280) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        }.getOrNull()
    } ?: return
    // Real blur needs Android 12; older devices show the picture as it is, dimmed.
    val blurModifier = if (Build.VERSION.SDK_INT >= 31) Modifier.blur(18.dp) else Modifier
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize().then(blurModifier),
    )
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = Prefs.backgroundDim / 100f)))
}
