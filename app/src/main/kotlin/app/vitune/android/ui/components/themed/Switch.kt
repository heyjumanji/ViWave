package app.vitune.android.ui.components.themed

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.Canvas
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.vitune.core.ui.LocalAppearance

@Composable
fun Switch(
    isChecked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: ((Boolean) -> Unit)? = null
) {
    val (colorPalette) = LocalAppearance.current

    androidx.compose.material3.Switch(
        checked = isChecked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colorPalette.onAccent,
            checkedTrackColor = colorPalette.accent,
            uncheckedThumbColor = colorPalette.textDisabled,
            uncheckedTrackColor = colorPalette.background1,
            uncheckedBorderColor = colorPalette.background1
        )
    )
}
