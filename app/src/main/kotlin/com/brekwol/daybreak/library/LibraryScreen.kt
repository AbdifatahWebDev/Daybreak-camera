package com.brekwol.daybreak.library

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.Photo
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakIconButton
import com.brekwol.daybreak.designsystem.R as DsR

@Composable
fun LibraryScreen(
    onOpenCamera: () -> Unit,
    onOpenPhoto: (Photo) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val container = LocalContext.current.container
    val colors = Daybreak.colors
    val changes by container.photos.changes.collectAsStateWithLifecycle()
    var photos by remember { mutableStateOf<List<Photo>?>(null) }
    LaunchedEffect(changes) {
        photos = container.photos.list()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface000),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DaybreakIconButton(
                    iconRes = DsR.drawable.ic_back,
                    contentDescription = "Back to camera",
                    onClick = onOpenCamera,
                )
                Text(
                    text = "Library",
                    style = Daybreak.type.title,
                    color = colors.ink,
                    modifier = Modifier.weight(1f),
                )
                DaybreakIconButton(
                    iconRes = DsR.drawable.ic_settings,
                    contentDescription = "Settings",
                    onClick = onOpenSettings,
                )
            }

            val list = photos
            when {
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.sun)
                }
                list.isEmpty() -> EmptyLibrary(onOpenCamera = onOpenCamera)
                else -> {
                    Text(
                        text = if (list.size == 1) "1 photo" else "${list.size} photos",
                        style = Daybreak.type.readout,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(list, key = { it.uri.toString() }) { photo ->
                            PhotoTile(photo = photo, onClick = { onOpenPhoto(photo) })
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .safeDrawingPadding()
                .padding(20.dp)
                .size(60.dp)
                .clip(CircleShape)
                .background(colors.ink)
                .clickable(onClick = onOpenCamera)
                .semantics { contentDescription = "Back to camera" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(DsR.drawable.ic_camera),
                contentDescription = null,
                tint = colors.surface000,
                modifier = Modifier.size(26.dp),
            )
        }
    }
}

@Composable
private fun PhotoTile(photo: Photo, onClick: () -> Unit) {
    val container = LocalContext.current.container
    val colors = Daybreak.colors
    val thumbnail by produceState<Bitmap?>(initialValue = null, photo.uri, photo.sizeBytes) {
        value = container.thumbnails.load(photo, 384)
    }
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface200)
            .clickable(onClick = onClick)
            .semantics { contentDescription = "Photo ${photo.name}" },
    ) {
        thumbnail?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0x9E050505))
                .padding(horizontal = 5.dp, vertical = 2.dp),
        ) {
            Text("JXL", style = Daybreak.type.readout, color = Color(0xFFF4EFE6))
        }
    }
}

@Composable
private fun EmptyLibrary(onOpenCamera: () -> Unit) {
    val colors = Daybreak.colors
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val ring = colors.line
        val sun = colors.sun
        val glow = colors.sunGlow
        Canvas(Modifier.size(140.dp)) {
            val c = size.width / 2f
            drawCircle(ring, radius = size.width * 0.4f, center = Offset(c, c), style = Stroke(width = 4.dp.toPx()))
            val r = size.width * 0.186f
            val horizon = size.height * 0.586f
            val half = Path().apply {
                moveTo(c - r, horizon)
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(c - r, horizon - r, c + r, horizon + r),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false,
                )
                close()
            }
            drawPath(half, glow)
            drawPath(half, sun, style = Stroke(width = 3.dp.toPx()))
        }
        Spacer(Modifier.height(20.dp))
        Text("No photos yet", style = Daybreak.type.title, color = colors.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Photos you take with Daybreak show up here. They're saved on this phone in DCIM/Daybreak.",
            style = Daybreak.type.body,
            color = colors.inkMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(999.dp))
                .background(colors.ink)
                .clickable(onClick = onOpenCamera)
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Take your first photo", style = Daybreak.type.label, color = colors.surface000)
        }
    }
}
