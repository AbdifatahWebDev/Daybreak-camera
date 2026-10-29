package com.brekwol.daybreak.pick

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.brekwol.daybreak.container
import com.brekwol.daybreak.data.Photo
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakIconButton
import com.brekwol.daybreak.designsystem.DaybreakTheme
import com.brekwol.daybreak.designsystem.R as DsR
import com.brekwol.daybreak.library.PhotoTile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Opens when another app asks for a photo ("choose an image") and the user picks
 * Daybreak. The app receives a JPEG copy of the chosen photo, which every app can read.
 */
class PickActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        setContent {
            DaybreakTheme(darkroom = true) {
                PickScreen(
                    preparing = preparing,
                    onCancel = { finish() },
                    onPick = { photo -> deliver(photo) },
                )
            }
        }
    }

    /** True while the chosen photo is being turned into a JPEG. */
    private var preparing by mutableStateOf(false)

    private fun deliver(photo: Photo) {
        if (preparing) return
        preparing = true
        lifecycleScope.launch {
            try {
                val file = container.exporter.export(photo)
                val uri = FileProvider.getUriForFile(this@PickActivity, "$packageName.files", file)
                val result = Intent().apply {
                    setDataAndType(uri, "image/jpeg")
                    clipData = ClipData.newRawUri(file.name, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                setResult(Activity.RESULT_OK, result)
                finish()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                preparing = false
                Toast.makeText(this@PickActivity, error.message ?: "Could not prepare the photo", Toast.LENGTH_LONG).show()
            }
        }
    }
}

@Composable
private fun PickScreen(preparing: Boolean, onCancel: () -> Unit, onPick: (Photo) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = context.container
    val colors = Daybreak.colors
    val photos by produceState<List<Photo>?>(initialValue = null) { value = container.photos.list() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.surface000)
            .safeDrawingPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DaybreakIconButton(DsR.drawable.ic_close, "Cancel", onClick = onCancel)
            Column(Modifier.weight(1f)) {
                Text("Choose a photo", style = Daybreak.type.title, color = colors.ink)
                Text("The app gets a JPEG copy.", style = Daybreak.type.caption, color = colors.inkMuted)
            }
            if (preparing) CircularProgressIndicator(color = colors.sun, strokeWidth = 2.dp)
        }
        val list = photos
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.sun)
            }
            list.isEmpty() -> Box(Modifier.fillMaxSize().padding(40.dp), contentAlignment = Alignment.Center) {
                Text(
                    "No photos yet. Take one with Daybreak first.",
                    style = Daybreak.type.body,
                    color = colors.inkMuted,
                    textAlign = TextAlign.Center,
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list, key = { it.uri.toString() }) { photo ->
                    PhotoTile(
                        photo = photo,
                        onClick = { onPick(photo) },
                    )
                }
            }
        }
    }
}
