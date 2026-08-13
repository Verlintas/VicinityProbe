package com.vicinityprobe.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.vicinityprobe.model.domain.QualityLevel
import com.vicinityprobe.model.domain.SeriesPt

@Composable
fun LineChart(
    points: List<SeriesPt>,
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

/** 数据质量徽章 */
@Composable
fun QualityPill(level: QualityLevel, modifier: Modifier = Modifier) {
    val colors: Triple<Color, Color, String> = when (level) {
        QualityLevel.EXCELLENT -> Triple(Color(0xFF1B5E20), Color(0xFFE8F5E9), "EXCELLENT")
        QualityLevel.GOOD -> Triple(Color(0xFF1565C0), Color(0xFFE3F2FD), "GOOD")
        QualityLevel.DEGRADED -> Triple(Color(0xFFE65100), Color(0xFFFFF3E0), "DEGRADED")
        QualityLevel.FAILED -> Triple(Color(0xFFB71C1C), Color(0xFFFFEBEE), "FAILED")
    }
    val (bg, fg, label) = colors
    Row(
        modifier = modifier
            .background(bg, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = fg, fontWeight = FontWeight.Bold)
    }
}

/** 键值对行 */
@Composable
fun KeyValueRow(label: String, value: String, primary: Boolean = false, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = if (primary) MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold)
            else MaterialTheme.typography.bodyMedium,
            color = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
