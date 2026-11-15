package app.vitune.android.ui.components.themed

import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.vitune.android.utils.disabled
import app.vitune.android.utils.medium
import app.vitune.android.utils.primary
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.utils.roundedShape

@Composable
fun DialogTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    val (colorPalette, typography) = LocalAppearance.current

    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = 36.dp.roundedShape,
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (primary) colorPalette.accent else Color.Transparent,
            contentColor = if (primary) colorPalette.onAccent else colorPalette.text,
            disabledContainerColor = if (primary) colorPalette.accent.copy(alpha = 0.5f) else Color.Transparent,
            disabledContentColor = colorPalette.textDisabled
        ),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = typography.xs.medium.let {
                when {
                    !enabled -> it.disabled
                    primary -> it.primary
                    else -> it
                }
            }
        )
    }
}
