package com.thermotrace.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Paleta.
 *
 * O azul-marinho é a cor institucional; o resto do sistema é semântico, e
 * essa é a parte que importa: num app de cadeia fria a cor comunica veredito.
 * Nunca use verde/âmbar/vermelho para decoração — se aparecer vermelho, é
 * porque algo saiu da faixa.
 */
val AzulProfundo = Color(0xFF081A2C)
val AzulClaro = Color(0xFF159BC4)
val AzulGelo = Color(0xFF132A40)
val CianoTrace = Color(0xFF35D9FF)
val IndigoPulse = Color(0xFF7187FF)

val VerdeConforme = Color(0xFF4DE4A6)
val VerdeFundo = Color(0xFF0C382E)
val AmbarAlerta = Color(0xFFFFC266)
val AmbarFundo = Color(0xFF3A2A0D)
val VermelhoExcursao = Color(0xFFFF7180)
val VermelhoFundo = Color(0xFF40191F)
val CinzaNeutro = Color(0xFF8EA4B8)

private val Claro = lightColorScheme(
    primary = Color(0xFF006780),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3F3FF),
    onPrimaryContainer = Color(0xFF003544),
    secondary = Color(0xFF445CBA),
    onSecondary = Color.White,
    error = VermelhoExcursao,
    onError = Color.White,
    errorContainer = VermelhoFundo,
    onErrorContainer = VermelhoExcursao,
    background = Color(0xFFF2F7FB),
    onBackground = Color(0xFF0B1E2B),
    surface = Color.White,
    onSurface = Color(0xFF0B1E2B),
    surfaceVariant = Color(0xFFE2EDF4),
    onSurfaceVariant = Color(0xFF425C6D),
    outline = Color(0xFF78909F),
)

private val Escuro = darkColorScheme(
    primary = CianoTrace,
    onPrimary = Color(0xFF002B36),
    primaryContainer = Color(0xFF10364C),
    onPrimaryContainer = Color(0xFFC8F3FF),
    secondary = IndigoPulse,
    onSecondary = Color(0xFF08123F),
    secondaryContainer = Color(0xFF202D5D),
    onSecondaryContainer = Color(0xFFDCE1FF),
    tertiary = VerdeConforme,
    onTertiary = Color(0xFF003826),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF7A2019),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF050D18),
    onBackground = Color(0xFFE8F6FF),
    surface = Color(0xFF0B1725),
    onSurface = Color(0xFFE8F6FF),
    surfaceVariant = Color(0xFF14263A),
    onSurfaceVariant = Color(0xFFA7BED0),
    outline = Color(0xFF35516C),
)

private val Tipografia = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 27.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.35).sp,
    ),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.15.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.25.sp),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.75.sp),
)

private val FormasTech = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(11.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TemaThermoTrace(escuro: Boolean = true, conteudo: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (escuro) Escuro else Claro,
        shapes = FormasTech,
        typography = Tipografia,
        content = conteudo,
    )
}
