package com.desmond.gptwake.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * The app theme.
 *
 * [MaterialExpressiveTheme] is what makes this Expressive rather than plain M3: it installs the
 * expressive [androidx.compose.material3.MotionScheme] (spatial springs at damping 0.6-0.8 instead
 * of a flat 0.9, so movement visibly overshoots) and the expressive shape scale. On material3 1.4.0
 * the `MotionScheme.expressive()` factory is `internal`, so using this composable is the only way to
 * get it — read it back through `MaterialTheme.motionScheme`, which is public.
 *
 * Dynamic colour is on by default. The previous Views theme never called
 * `DynamicColors.applyToActivitiesIfAvailable`, so the app rendered baseline purple while the system
 * palette was something else entirely.
 */
@Composable
fun GptWakeTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        darkTheme -> BrandDarkScheme
        else -> BrandLightScheme
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
