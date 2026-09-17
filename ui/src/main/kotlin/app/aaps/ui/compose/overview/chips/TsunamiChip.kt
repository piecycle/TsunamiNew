package app.aaps.ui.compose.overview.chips

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import app.aaps.core.ui.compose.AapsSpacing
import app.aaps.core.ui.compose.icons.IcTsunami

// Assuming a data class like this exists or will be added
data class TsunamiUiState(
    val isActive: Boolean,
    val text: String, // e.g., "12m" or "n/a"
    val duration: String
)

@Composable
internal fun TsunamiChip(
    state: TsunamiUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (state.isActive) {
        // Replace with actual Tsunami color
        MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
    } else {
        Color.Transparent
    }
    
    val contentColor = if (state.isActive) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        Surface(
            shape = RoundedCornerShape(AapsSpacing.chipCornerRadius),
            color = backgroundColor,
            onClick = onClick,
            modifier = modifier
                .heightIn(min = AapsSpacing.chipHeight)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = AapsSpacing.medium, vertical = AapsSpacing.small)
            ) {
                Icon(
                    imageVector = IcTsunami,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(AapsSpacing.chipIconSize)
                )
                Text(
                    text = state.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    modifier = Modifier.padding(start = AapsSpacing.medium)
                )
            }
        }
    }
}
