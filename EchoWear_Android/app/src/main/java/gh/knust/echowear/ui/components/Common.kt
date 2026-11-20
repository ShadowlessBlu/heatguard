package gh.knust.echowear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gh.knust.echowear.ui.theme.LocalStatusPalette

/** Section heading with an optional trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium,
                 fontWeight = FontWeight.SemiBold)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing?.invoke()
    }
}

enum class BadgeTone { OK, WARN, BAD, NEUTRAL, INFO }

/** Small status pill. Ported from the dashboards' Badge component. */
@Composable
fun StatusBadge(
    text: String,
    tone: BadgeTone = BadgeTone.NEUTRAL,
    modifier: Modifier = Modifier,
) {
    val palette = LocalStatusPalette.current
    val colour = when (tone) {
        BadgeTone.OK -> palette.ok
        BadgeTone.WARN -> palette.warn
        BadgeTone.BAD -> palette.bad
        BadgeTone.INFO -> MaterialTheme.colorScheme.primary
        BadgeTone.NEUTRAL -> palette.idle
    }
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(colour.copy(alpha = 0.14f))
            // The tint alone is not enough at 4.5:1 for everyone, so the pill
            // also carries an outline in the same hue.
            .border(1.dp, colour.copy(alpha = 0.55f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = colour,
        )
    }
}

/** A coloured dot with a label - link state, GPS fix, SIM registration. */
@Composable
fun StatusDot(label: String, tone: BadgeTone, modifier: Modifier = Modifier) {
    val palette = LocalStatusPalette.current
    val colour = when (tone) {
        BadgeTone.OK -> palette.ok
        BadgeTone.WARN -> palette.warn
        BadgeTone.BAD -> palette.bad
        BadgeTone.INFO -> MaterialTheme.colorScheme.primary
        BadgeTone.NEUTRAL -> palette.idle
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(colour))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * The vitals tile. Ported from VitalCard.tsx, with the value promoted to a
 * display-size type: the number is the only thing most people will read, and
 * at arm's length on a phone propped by a bedside it has to win.
 */
@Composable
fun VitalCard(
    label: String,
    value: String,
    unit: String = "",
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.NEUTRAL,
    caption: String? = null,
    trend: List<Float> = emptyList(),
    icon: ImageVector? = null,
) {
    val palette = LocalStatusPalette.current
    val accent = when (tone) {
        BadgeTone.OK -> palette.ok
        BadgeTone.WARN -> palette.warn
        BadgeTone.BAD -> palette.bad
        BadgeTone.INFO -> MaterialTheme.colorScheme.primary
        BadgeTone.NEUTRAL -> MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                icon?.let {
                    Icon(it, null, Modifier.size(16.dp), tint = accent)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accent,
                )
                if (unit.isNotBlank()) {
                    Text(
                        " $unit",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            if (trend.size > 1) {
                Spacer(Modifier.height(6.dp))
                Sparkline(trend, Modifier.fillMaxWidth(), accent)
            }
            caption?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Label/value row used throughout the detail panels. */
@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Color.Unspecified,
) {
    Row(
        modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (valueColor == Color.Unspecified) {
                MaterialTheme.colorScheme.onSurface
            } else {
                valueColor
            },
            textAlign = TextAlign.End,
        )
    }
}

/** Ported from the dashboards' EmptyState. */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier.fillMaxWidth().padding(vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * Ported from ErrorState.tsx. Errors here are usually recoverable and usually
 * the phone's fault (Bluetooth off, permission missing), so the component
 * takes a retry action rather than just reporting.
 */
@Composable
fun ErrorState(
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.semantics { contentDescription = "Problem: $message" },
            )
            action?.let {
                Spacer(Modifier.height(8.dp))
                it()
            }
        }
    }
}

/** Thin labelled progress bar, for battery and signal. */
@Composable
fun MeterBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    tone: BadgeTone = BadgeTone.OK,
) {
    val palette = LocalStatusPalette.current
    val colour = when (tone) {
        BadgeTone.OK -> palette.ok
        BadgeTone.WARN -> palette.warn
        BadgeTone.BAD -> palette.bad
        BadgeTone.INFO -> MaterialTheme.colorScheme.primary
        BadgeTone.NEUTRAL -> palette.idle
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(colour),
        )
    }
}
