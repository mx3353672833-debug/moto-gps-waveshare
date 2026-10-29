package top.maler.motogps.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import top.maler.motogps.model.ChinaCoordinateTransform
import top.maler.motogps.model.GCJ02Point
import top.maler.motogps.model.WGS84Point
import top.maler.motogps.nav.RoutePreviewCandidate

/**
 * Route-selection overview drawn locally, so the phone needs no third-party
 * map SDK or key. Everything is projected in GCJ-02 (the route geometry's own
 * system); WGS84 endpoints are converted once at this boundary.
 */
@Composable
fun RouteOverviewMap(
    candidates: List<RoutePreviewCandidate>,
    selectedId: String?,
    origin: WGS84Point?,
    destination: WGS84Point,
    description: String,
    modifier: Modifier = Modifier,
) {
    val originGcj = remember(origin) { origin?.let(ChinaCoordinateTransform::wgs84ToGcj02) }
    val destinationGcj = remember(destination) { ChinaCoordinateTransform.wgs84ToGcj02(destination) }
    val background = MaterialTheme.colorScheme.surfaceContainerHigh
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val selectedColor = MaterialTheme.colorScheme.primary
    val casing = MaterialTheme.colorScheme.surfaceContainerLowest
    val scaleColor = MaterialTheme.colorScheme.onSurfaceVariant

    val ordered = candidates.sortedBy { if (it.id == selectedId) 1 else 0 }
    val allPoints = buildList {
        candidates.forEach { addAll(it.route.polyline) }
        originGcj?.let(::add)
        add(destinationGcj)
    }
    val projection = remember(allPoints) { Projection.fit(allPoints) }

    Box(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(background)
            drawGrid(grid)
            val fitted = projection?.scaled(size.width, size.height, padding = 36.dp.toPx()) ?: return@Canvas
            for (candidate in ordered) {
                val selected = candidate.id == selectedId
                val path = Path()
                candidate.route.polyline.forEachIndexed { index, point ->
                    val offset = fitted.project(point)
                    if (index == 0) path.moveTo(offset.x, offset.y) else path.lineTo(offset.x, offset.y)
                }
                if (selected) {
                    drawPath(path, casing, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                drawPath(
                    path,
                    if (selected) selectedColor else MotoColors.RouteAlternate,
                    style = Stroke((if (selected) 6 else 4).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            originGcj?.let { drawEndpoint(fitted.project(it), selectedColor) }
            drawEndpoint(fitted.project(destinationGcj), MotoColors.Red)
            drawScaleBar(fitted, scaleColor)
        }
        if (projection != null) {
            Text(
                "高德驾车路线 · 本地绘制",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
            )
        }
    }
}

private class Projection(
    val minimumLatitude: Double,
    val maximumLatitude: Double,
    val minimumLongitude: Double,
    val maximumLongitude: Double,
) {
    /** Local equirectangular metres; plenty for a single route overview. */
    private val metresPerLongitude = 111_195.0 * cos(Math.toRadians((minimumLatitude + maximumLatitude) / 2))

    val widthM: Double get() = max(1.0, (maximumLongitude - minimumLongitude) * metresPerLongitude)
    val heightM: Double get() = max(1.0, (maximumLatitude - minimumLatitude) * 111_195.0)

    fun scaled(width: Float, height: Float, padding: Float): Fitted {
        val usableWidth = max(1f, width - padding * 2)
        val usableHeight = max(1f, height - padding * 2)
        val scale = min(usableWidth / widthM, usableHeight / heightM)
        val offsetX = (width - widthM * scale) / 2
        val offsetY = (height - heightM * scale) / 2
        return Fitted(this, scale, offsetX, offsetY, metresPerLongitude)
    }

    companion object {
        fun fit(points: List<GCJ02Point>): Projection? {
            val valid = points.filter { it.latitudeDeg.isFinite() && it.longitudeDeg.isFinite() }
            if (valid.isEmpty()) return null
            return Projection(
                valid.minOf { it.latitudeDeg },
                valid.maxOf { it.latitudeDeg },
                valid.minOf { it.longitudeDeg },
                valid.maxOf { it.longitudeDeg },
            )
        }
    }
}

private class Fitted(
    val projection: Projection,
    val scale: Double,
    val offsetX: Double,
    val offsetY: Double,
    val metresPerLongitude: Double,
) {
    fun project(point: GCJ02Point): Offset {
        val x = (point.longitudeDeg - projection.minimumLongitude) * metresPerLongitude * scale + offsetX
        val y = (projection.maximumLatitude - point.latitudeDeg) * 111_195.0 * scale + offsetY
        return Offset(x.toFloat(), y.toFloat())
    }
}

private fun DrawScope.drawGrid(color: Color) {
    val step = 32.dp.toPx()
    var x = 0f
    while (x < size.width) {
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        x += step
    }
    var y = 0f
    while (y < size.height) {
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        y += step
    }
}

private fun DrawScope.drawEndpoint(center: Offset, color: Color) {
    drawCircle(Color.White, radius = 10.dp.toPx(), center = center)
    drawCircle(color, radius = 7.dp.toPx(), center = center)
}

private fun DrawScope.drawScaleBar(fitted: Fitted, color: Color) {
    val targetPx = 80.dp.toPx()
    val metres = targetPx / fitted.scale
    if (!metres.isFinite() || metres <= 0) return
    val magnitude = 10.0.pow(floor(log10(metres)))
    val nice = listOf(1.0, 2.0, 5.0, 10.0).map { it * magnitude }.last { it <= metres }
    val length = (nice * fitted.scale).toFloat()
    val start = Offset(12.dp.toPx(), size.height - 14.dp.toPx())
    drawLine(color, start, start.copy(x = start.x + length), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            this.color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(), (color.red * 255).toInt(), (color.green * 255).toInt(), (color.blue * 255).toInt(),
            )
            textSize = 11.dp.toPx()
            isAntiAlias = true
        }
        val label = if (nice >= 1_000) "${(nice / 1_000).let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} km" else "${nice.toInt()} m"
        drawText(label, start.x, start.y - 5.dp.toPx(), paint)
    }
}
