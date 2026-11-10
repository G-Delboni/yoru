// Yozakura em Compose: céu em cima, linha do horizonte, água embaixo. Só cinzas.
// As mesmas cores de app/static/yoru.css.
package app.yoru.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Yz {
    val sky = Color(0xFF0B0B0B)
    val skyLow = Color(0xFF171717)
    val water = Color(0xFF0E0E0E)
    val line = Color(0xFF2A2A2A)
    val text = Color(0xFFE6E6E6)
    val text2 = Color(0xFF9A9A9A)
    // 4,7:1 sobre o céu: o mínimo para texto pequeno ser legível (WCAG AA)
    val text3 = Color(0xFF7C7C7C)
    val moon = Color(0xFFD9D9D9)
}

// Serif para títulos, números e kanji (no site é a Shippori Mincho; aqui a serifa do sistema,
// até colocarmos a fonte em res/font). Sans leve para o resto.
val Serif = FontFamily.Serif
val Sans = FontFamily.SansSerif

private val colors = darkColorScheme(
    primary = Yz.text,
    onPrimary = Yz.sky,
    background = Yz.sky,
    onBackground = Yz.text,
    surface = Yz.water,
    onSurface = Yz.text,
    surfaceVariant = Yz.water,
    onSurfaceVariant = Yz.text2,
    outline = Yz.line,
    secondary = Yz.text2,
)

// Os estilos principais NÃO levam cor: a cor vem do componente (LocalContentColor).
// Se levassem, um botão claro herdaria texto branco (bodyLarge é o estilo padrão
// do MaterialTheme) e ficaria branco sobre branco.
private val type = Typography(
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 36.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.6.sp, color = Yz.text2),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, color = Yz.text2),
)

@Composable
fun YoruTheme(content: @Composable () -> Unit) {
    // O Yoru é sempre noite: o tema claro do sistema é ignorado de propósito.
    MaterialTheme(colorScheme = colors, typography = type) {
        // Texto sem cor própria fica claro sobre o fundo escuro.
        CompositionLocalProvider(LocalContentColor provides Yz.text, content = content)
    }
}
