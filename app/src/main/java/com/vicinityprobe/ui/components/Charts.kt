package com.vicinityprobe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vicinityprobe.model.Metric
import com.vicinityprobe.model.ProbeStatus
import com.vicinityprobe.model.SeriesPoint
import com.vicinityprobe.model.trBilingual

@Composable
fun LineChart(
    points: List<SeriesPoint>,
    label: String,
    unit: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        if (points.size < 2) {
            Text("—", style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val values = points.map { it.v }
        val minV = values.min()
        val maxV = values.max()
        val span = (maxV - minV).let { if (it == 0.0) 1.0 else it }
        val tickPaint = android.graphics.Paint().apply {
            textSize = 22f
            setColor(android.graphics.Color.GRAY)
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            val w = size.width
            val h = size.height
            val pad = 8.dp.toPx()
            val path = Path()
            points.forEachIndexed { i, p ->
                val x = pad + (w - 2 * pad) * (i.toFloat() / (points.size - 1))
                val y = pad + (h - 2 * pad) * (1f - ((p.v - minV) / span).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = color, style = Stroke(width = 3f))
            drawLine(Color.Gray.copy(alpha = 0.4f), Offset(pad, h - pad), Offset(w - pad, h - pad), 1f)
            drawContext.canvas.nativeCanvas.drawText(minV.toString(), 0f, h - 8.dp.toPx(), tickPaint)
            drawContext.canvas.nativeCanvas.drawText(maxV.toString(), 0f, 30f, tickPaint)
        }
        Text("min $minV · max $maxV $unit", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun RadarChart(
    axes: List<Pair<String, Double>>,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    if (axes.size < 3) return
    val n = axes.size
    val size = 180.dp
    val labelPaint = android.graphics.Paint().apply {
        textSize = 24f
        setColor(android.graphics.Color.GRAY)
    }
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(modifier = Modifier.width(size).height(size)) {
            val cx = size.toPx() / 2
            val cy = size.toPx() / 2
            val radius = size.toPx() / 2 - 12.dp.toPx()
            for (g in 1..4) {
                val r = radius * g / 4
                val path = Path()
                for (i in 0 until n) {
                    val angle = (2 * Math.PI * i / n - Math.PI / 2)
                    val x = cx + r * Math.cos(angle).toFloat()
                    val y = cy + r * Math.sin(angle).toFloat()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                drawPath(path, color = Color.Gray.copy(alpha = 0.3f), style = Stroke(width = 1f))
            }
            for (i in 0 until n) {
                val angle = (2 * Math.PI * i / n - Math.PI / 2)
                drawLine(
                    Color.Gray.copy(alpha = 0.3f),
                    Offset(cx, cy),
                    Offset(cx + radius * Math.cos(angle).toFloat(), cy + radius * Math.sin(angle).toFloat()),
                    1f,
                )
                val label = axes[i].first
                val lx = cx + (radius + 14.dp.toPx()) * Math.cos(angle).toFloat()
                val ly = cy + (radius + 14.dp.toPx()) * Math.sin(angle).toFloat()
                labelPaint.textAlign = when {
                    lx > cx -> android.graphics.Paint.Align.LEFT
                    lx < cx - 10 -> android.graphics.Paint.Align.RIGHT
                    else -> android.graphics.Paint.Align.CENTER
                }
                drawContext.canvas.nativeCanvas.drawText(label, lx, ly, labelPaint)
            }
            val polygon = Path()
            axes.forEachIndexed { i, (_, score) ->
                val angle = (2 * Math.PI * i / n - Math.PI / 2)
                val r = radius * (score / 100.0).toFloat().coerceIn(0f, 1f)
                val x = cx + r * Math.cos(angle).toFloat()
                val y = cy + r * Math.sin(angle).toFloat()
                if (i == 0) polygon.moveTo(x, y) else polygon.lineTo(x, y)
            }
            polygon.close()
            drawPath(polygon, color = color.copy(alpha = 0.5f))
            drawPath(polygon, color = color, style = Stroke(width = 2.5f))
        }
    }
}

@Composable
fun StatusPill(status: ProbeStatus, modifier: Modifier = Modifier) {
    val (bg, fg) = when (status) {
        ProbeStatus.OK -> Color(0xFF1B5E20) to Color(0xFFE8F5E9)
        ProbeStatus.NO_HARDWARE, ProbeStatus.SKIPPED -> Color(0xFF616161) to Color(0xFFEEEEEE)
        ProbeStatus.PERMISSION_MISSING, ProbeStatus.FEATURE_OFF -> Color(0xFFE65100) to Color(0xFFFFF3E0)
        ProbeStatus.FAILED -> Color(0xFFB71C1C) to Color(0xFFFFEBEE)
    }
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            when (status) {
                ProbeStatus.OK -> "OK"
                ProbeStatus.NO_HARDWARE -> "N/A"
                ProbeStatus.PERMISSION_MISSING -> "PERM"
                ProbeStatus.FEATURE_OFF -> "OFF"
                ProbeStatus.FAILED -> "FAIL"
                ProbeStatus.SKIPPED -> "SKIP"
            },
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
fun MetricGrid(
    metrics: List<Metric>,
    lang: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        metrics.forEach { m ->
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    trBilingual(m.label, lang),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    trBilingual(m.value, lang) + (m.unit?.let { " $it" } ?: ""),
                    style = if (m.isPrimary) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
                    else MaterialTheme.typography.bodyMedium,
                    color = if (m.isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
