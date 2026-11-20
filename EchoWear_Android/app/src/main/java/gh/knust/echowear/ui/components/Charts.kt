package gh.knust.echowear.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import gh.knust.echowear.ui.theme.LocalStatusPalette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Charts drawn straight onto a Canvas.
 *
 * The React dashboards used Recharts. Pulling an equivalent Android charting
 * library in would add a few hundred kilobytes and a theming layer to fight,
 * for six chart types whose whole job is to draw a polyline inside a box.
 * Canvas also means the colours come from the same accessibility palette as
 * everything else, so high-contrast mode actually reaches the charts - which
 * is exactly where a hard-coded library palette would have broken it.
 */

private fun niceBounds(values: List<Float>, padFraction: Float = 0.08f): Pair<Float, Float> {
    if (values.isEmpty()) return 0f to 1f
    var lo = values.min()
    var hi = values.max()
    if (abs(hi - lo) < 1e-3f) { lo -= 1f; hi += 1f }
    val pad = (hi - lo) * padFraction
    return (lo - pad) to (hi + pad)
}

private fun DrawScope.drawGrid(rows: Int, colour: Color) {
    for (i in 0..rows) {
        val y = size.height * i / rows
        drawLine(
            color = colour,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)),
        )
    }
}

/**
 * A trend line with an optional shaded band. Points are plotted in the order
 * given, oldest first.
 */
@Composable
fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    fill: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 140.dp,
    yLabel: (Float) -> String = { "%.0f".format(it) },
    band: ClosedFloatingPointRange<Float>? = null,
) {
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
    val bandColour = LocalStatusPalette.current.ok.copy(alpha = 0.12f)
    val (lo, hi) = niceBounds(values)

    Row(modifier.fillMaxWidth().height(height)) {
        // Axis labels live outside the Canvas so they pick up the scaled
        // typography rather than a hard-coded paint size.
        Column(
            Modifier.padding(end = 6.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(yLabel(hi), style = MaterialTheme.typography.labelSmall)
            Text(yLabel((hi + lo) / 2f), style = MaterialTheme.typography.labelSmall)
            Text(yLabel(lo), style = MaterialTheme.typography.labelSmall)
        }

        Canvas(Modifier.weight(1f).fillMaxWidth()) {
            drawGrid(4, grid)
            if (values.size < 2) return@Canvas

            val span = (hi - lo).takeIf { it > 0f } ?: 1f
            fun yOf(v: Float) = size.height * (1f - (v - lo) / span)
            fun xOf(i: Int) = size.width * i / (values.size - 1).toFloat()

            band?.let { b ->
                val top = yOf(min(b.endInclusive, hi))
                val bottom = yOf(max(b.start, lo))
                drawRect(
                    color = bandColour,
                    topLeft = Offset(0f, top),
                    size = Size(size.width, (bottom - top).coerceAtLeast(0f)),
                )
            }

            val line = Path().apply {
                moveTo(xOf(0), yOf(values[0]))
                for (i in 1 until values.size) lineTo(xOf(i), yOf(values[i]))
            }
            if (fill) {
                val area = Path().apply {
                    addPath(line)
                    lineTo(size.width, size.height)
                    lineTo(0f, size.height)
                    close()
                }
                drawPath(
                    area,
                    brush = Brush.verticalGradient(
                        listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0f)),
                    ),
                )
            }
            drawPath(line, color = color, style = Stroke(width = 3f))
            drawCircle(color, radius = 4f, center = Offset(xOf(values.size - 1),
                yOf(values.last())))
        }
    }
}

/** A tiny inline trend, for the card headers. */
@Composable
fun Sparkline(
    values: List<Float>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Canvas(modifier.height(28.dp)) {
        if (values.size < 2) return@Canvas
        val (lo, hi) = niceBounds(values, 0.05f)
        val span = (hi - lo).takeIf { it > 0f } ?: 1f
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = size.width * i / (values.size - 1).toFloat()
            val y = size.height * (1f - (v - lo) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = Stroke(width = 2.5f))
    }
}

/** Labelled vertical bars - the daily event histogram. */
@Composable
fun BarChart(
    entries: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    height: androidx.compose.ui.unit.Dp = 130.dp,
    maxLabels: Int = 7,
) {
    if (entries.isEmpty()) {
        EmptyState("Nothing recorded in this period yet.", modifier)
        return
    }
    val peak = max(1f, entries.maxOf { it.second })
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val step = max(1, entries.size / maxLabels)

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            drawGrid(3, grid)
            val slot = size.width / entries.size
            val barWidth = slot * 0.62f
            entries.forEachIndexed { i, (_, v) ->
                val h = size.height * (v / peak)
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * slot + (slot - barWidth) / 2f, size.height - h),
                    size = Size(barWidth, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            entries.forEachIndexed { i, (label, _) ->
                Text(
                    text = if (i % step == 0) label else "",
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Three-axis accelerometer trace.
 *
 * All three axes share one vertical scale on purpose: the thing a clinician
 * is looking for in a fall trace is the moment the magnitude leaves the
 * +/-1 g envelope, and auto-scaling each axis to its own range would hide
 * exactly that.
 */
@Composable
fun AccelChart(
    samples: List<FloatArray>,
    modifier: Modifier = Modifier,
    height: androidx.compose.ui.unit.Dp = 160.dp,
) {
    val palette = LocalStatusPalette.current
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    val axisColours = listOf(palette.chart[0], palette.chart[1], palette.chart[2])

    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            drawGrid(4, grid)
            if (samples.size < 2) return@Canvas

            val all = samples.flatMap { listOf(it[0], it[1], it[2]) }
            val bound = max(2f, all.maxOf { abs(it) } * 1.1f)
            fun yOf(v: Float) = size.height * (1f - (v + bound) / (2 * bound))

            // The zero line, because a reader has to see which way is up.
            drawLine(
                color = grid,
                start = Offset(0f, yOf(0f)),
                end = Offset(size.width, yOf(0f)),
                strokeWidth = 1.5f,
            )

            for (axis in 0..2) {
                val path = Path()
                samples.forEachIndexed { i, s ->
                    val x = size.width * i / (samples.size - 1).toFloat()
                    val y = yOf(s[axis])
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, color = axisColours[axis], style = Stroke(width = 2.2f))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            listOf("X", "Y", "Z").forEachIndexed { i, name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(10.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(axisColours[i]),
                    )
                    Text(
                        "  $name",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                "g",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A 0..1 gauge, used for the ML score and the risk index. */
@Composable
fun ScoreGauge(
    score: Float,
    modifier: Modifier = Modifier,
    threshold: Float = 0.5f,
) {
    val palette = LocalStatusPalette.current
    val track = MaterialTheme.colorScheme.surfaceVariant
    // Hoisted: MaterialTheme is a composition read and the DrawScope lambda
    // below is not a composable scope.
    val tick = MaterialTheme.colorScheme.onSurface
    val bar = when {
        score >= threshold -> palette.bad
        score >= threshold * 0.6f -> palette.warn
        else -> palette.ok
    }
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        drawRoundRect(
            color = track,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f),
        )
        drawRoundRect(
            color = bar,
            size = Size(size.width * score.coerceIn(0f, 1f), size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(7f, 7f),
        )
        val x = size.width * threshold.coerceIn(0f, 1f)
        drawLine(
            color = tick,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 2f,
        )
    }
}
