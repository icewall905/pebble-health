package org.tdgs.ph.workout

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import kotlin.math.*

private data class MapPoint(val x: Double, val y: Double)
private data class Tile(val zoom: Int, val x: Int, val y: Int)
private fun project(fix: Fix, originX: Double): MapPoint {
    val x = (fix.lon + 180) / 360
    val latitude = Math.toRadians(fix.lat.coerceIn(-85.05112878, 85.05112878))
    return MapPoint(x - round(x - originX), (1 - asinh(tan(latitude)) / PI) / 2)
}

private object MapTiles {
    private var client: OkHttpClient? = null
    @Synchronized fun client(directory: File): OkHttpClient = client ?: OkHttpClient.Builder()
        .cache(Cache(File(directory, "osm-tiles"), 32L * 1024 * 1024))
        .followRedirects(false).build().also { client = it }
}

@Composable
internal fun WorkoutMap(route: List<Fix>, follow: Boolean = false) {
    if (route.isEmpty()) return
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val prefs = remember { context.getSharedPreferences("workout_setup", 0) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("street_map", false)) }
    if (!enabled) {
        RouteTrace(route)
        TextButton(onClick = {
            prefs.edit().putBoolean("street_map", true).apply()
            enabled = true
        }) { Text("Show street map") }
        Text("Street maps request tiles for the visible area from OpenStreetMap.", style = MaterialTheme.typography.bodySmall)
        return
    }
    val stride = (route.size / 2000).coerceAtLeast(1)
    val points = remember(route) {
        val origin = (route.first().lon + 180) / 360
        route.filterIndexed { index, fix ->
            index % stride == 0 || index == route.lastIndex || route.getOrNull(index - 1)?.segment != fix.segment
        }.map { project(it, origin) to it.segment }
    }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var delta by remember { mutableStateOf(MapPoint(0.0, 0.0)) }
    var zoomAdjustment by remember { mutableIntStateOf(0) }
    val minX = points.minOf { it.first.x }
    val maxX = points.maxOf { it.first.x }
    val minY = points.minOf { it.first.y }
    val maxY = points.maxOf { it.first.y }
    val fit = min(
        size.width / max(maxX - minX, 0.000001),
        size.height / max(maxY - minY, 0.000001)
    ) * 0.76 / 256
    val baseZoom = if (follow) 16 else floor(log2(fit.coerceAtLeast(1.0))).toInt().coerceIn(2, 17)
    val zoom = (baseZoom + zoomAdjustment).coerceIn(2, 19)
    val world = 256.0 * (1 shl zoom)
    val origin = if (follow) points.last().first else MapPoint((minX + maxX) / 2, (minY + maxY) / 2)
    val centre = MapPoint(origin.x + delta.x, (origin.y + delta.y).coerceIn(0.0, 1.0))
    val left = centre.x * world - size.width / 2.0
    val top = centre.y * world - size.height / 2.0
    val visible = remember(zoom, size, floor(left / 256), floor(top / 256),
        floor((left + size.width) / 256), floor((top + size.height) / 256)) {
        if (size == IntSize.Zero) emptyList() else buildList {
            for (y in floor(top / 256).toInt()..floor((top + size.height) / 256).toInt()) {
                if (y !in 0 until (1 shl zoom)) continue
                for (x in floor(left / 256).toInt()..floor((left + size.width) / 256).toInt()) add(Tile(zoom, x, y))
            }
        }
    }
    val images by produceState<Map<Tile, ImageBitmap>>(emptyMap(), visible) {
        value = value.filterKeys { it in visible }
        for (tile in visible) {
            if (tile in value) continue
            val bitmap = withContext(Dispatchers.IO) {
                val x = Math.floorMod(tile.x, 1 shl tile.zoom)
                val request = Request.Builder().url("https://tile.openstreetmap.org/${tile.zoom}/$x/${tile.y}.png")
                    .header("User-Agent", "PebbleHealth/1.0 (+https://github.com/icewall905/pebble-health)").build()
                try {
                    MapTiles.client(context.cacheDir).newCall(request).execute().use { response ->
                        if (response.isSuccessful) BitmapFactory.decodeStream(response.body.byteStream())?.asImageBitmap() else null
                    }
                } catch (_: java.io.IOException) { null }
            }
            if (bitmap != null) value = value + (tile to bitmap)
        }
    }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceVariant) {
        Box(Modifier.fillMaxWidth().height(250.dp).onSizeChanged { size = it }) {
            Canvas(Modifier.fillMaxSize().pointerInput(world) {
                detectDragGestures { change, drag ->
                    change.consume()
                    delta = MapPoint((delta.x - drag.x / world).coerceIn(-1.0, 1.0),
                        (delta.y - drag.y / world).coerceIn(-1.0, 1.0))
                }
            }) {
                fun offset(p: MapPoint) = Offset((p.x * world - left).toFloat(), (p.y * world - top).toFloat())
                clipRect {
                    images.forEach { (tile, bitmap) ->
                        drawImage(bitmap, dstOffset = IntOffset((tile.x * 256 - left).roundToInt(),
                            (tile.y * 256 - top).roundToInt()), dstSize = IntSize(256, 256))
                    }
                    points.zipWithNext().forEach { (a, b) ->
                        if (a.second == b.second) {
                            drawLine(Color.White, offset(a.first), offset(b.first), 6.dp.toPx(), StrokeCap.Round)
                            drawLine(Color(0xff087d68), offset(a.first), offset(b.first), 3.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    drawCircle(Color.White, 7.dp.toPx(), offset(points.last().first))
                    drawCircle(Color(0xff087d68), 5.dp.toPx(), offset(points.last().first))
                }
            }
            if (images.isEmpty()) Text("Street tiles unavailable or loading", Modifier.align(Alignment.TopStart).padding(12.dp),
                style = MaterialTheme.typography.bodySmall)
            Surface(Modifier.align(Alignment.TopEnd).padding(6.dp), shape = MaterialTheme.shapes.medium) {
                Row {
                    IconButton(onClick = { zoomAdjustment = zoom - baseZoom - 1 }, enabled = zoom > 2) { Text("−") }
                    IconButton(onClick = { zoomAdjustment = zoom - baseZoom + 1 }, enabled = zoom < 19) { Text("+") }
                }
            }
            Surface(Modifier.align(Alignment.BottomEnd), color = MaterialTheme.colorScheme.surface) {
                TextButton(onClick = { uri.openUri("https://www.openstreetmap.org/copyright") }) {
                    Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    Row {
        TextButton(onClick = { delta = MapPoint(0.0, 0.0); zoomAdjustment = 0 }) { Text(if (follow) "Recenter" else "Fit route") }
        TextButton(onClick = { prefs.edit().putBoolean("street_map", false).apply(); enabled = false }) { Text("Hide street map") }
    }
}
