package app.vitune.android.ui.components.themed

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.vitune.core.ui.LocalAppearance
import app.vitune.core.ui.surface
import app.vitune.core.ui.utils.roundedShape

@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val (colorPalette) = LocalAppearance.current

    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        shape = 16.dp.roundedShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = colorPalette.surface,
            contentColor = colorPalette.text
        ),
        modifier = modifier.size(62.dp)
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
    }
}
