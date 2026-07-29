package com.desmond.gptwake.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Brand seed. A warm coral, chosen because the launcher icon has to hold its own against whatever
 * the system palette happens to be — on the reference tablet that is a blue (#3678FF), and a blue
 * icon would disappear into it.
 *
 * This is also the seed the launcher icon and splash screen are drawn from, so changing it means
 * changing `res/values/colors.xml` to match.
 */
val BrandSeed = Color(0xFFFF6A3D)

/**
 * Fallback schemes for when dynamic colour is unavailable.
 *
 * Note that at `minSdk 32` every supported device has dynamic colour, so these are unreachable in
 * production today. They are kept because they make `@Preview` deterministic and because they cost
 * nothing if the floor is ever lowered.
 */
val BrandLightScheme = lightColorScheme(
    primary = Color(0xFF8F4C33),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDBCF),
    onPrimaryContainer = Color(0xFF3A0B00),
    secondary = Color(0xFF77574C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFDBCF),
    onSecondaryContainer = Color(0xFF2C150D),
    tertiary = Color(0xFF6C5D2F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5E1A7),
    onTertiaryContainer = Color(0xFF231B00),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFF8F6),
    onBackground = Color(0xFF231917),
    surface = Color(0xFFFFF8F6),
    onSurface = Color(0xFF231917),
    surfaceVariant = Color(0xFFF5DED8),
    onSurfaceVariant = Color(0xFF53433F),
    outline = Color(0xFF85736E),
    outlineVariant = Color(0xFFD8C2BC),
)

val BrandDarkScheme = darkColorScheme(
    primary = Color(0xFFFFB59B),
    onPrimary = Color(0xFF56200A),
    primaryContainer = Color(0xFF723523),
    onPrimaryContainer = Color(0xFFFFDBCF),
    secondary = Color(0xFFE7BDB0),
    onSecondary = Color(0xFF442A20),
    secondaryContainer = Color(0xFF5D4035),
    onSecondaryContainer = Color(0xFFFFDBCF),
    tertiary = Color(0xFFD8C58D),
    onTertiary = Color(0xFF3B2F05),
    tertiaryContainer = Color(0xFF534619),
    onTertiaryContainer = Color(0xFFF5E1A7),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF1A110F),
    onBackground = Color(0xFFF1DFDA),
    surface = Color(0xFF1A110F),
    onSurface = Color(0xFFF1DFDA),
    surfaceVariant = Color(0xFF53433F),
    onSurfaceVariant = Color(0xFFD8C2BC),
    outline = Color(0xFFA08C87),
    outlineVariant = Color(0xFF53433F),
)
