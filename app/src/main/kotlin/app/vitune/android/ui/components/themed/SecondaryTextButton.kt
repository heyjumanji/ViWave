package app.vitune.android.ui.components.themed

import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.vitune.android.utils.center
import app.vitune.android.utils.disabled
import app.vitune.android.utils.medium
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.surface
import app.vitune.core.ui.utils.roundedShape

@Composable
fun SecondaryTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    alternative: Boolean = false
) {
    val (colorPalette, typography) = LocalAppearance.current

    androidx.compose.material3.TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = 16.dp.roundedShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (alternative) colorPalette.background0 else colorPalette.surface,
            contentColor = colorPalette.text
        ),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = typography.xxs.medium.center.let { if (enabled) it else it.disabled }
        )
    }
}
