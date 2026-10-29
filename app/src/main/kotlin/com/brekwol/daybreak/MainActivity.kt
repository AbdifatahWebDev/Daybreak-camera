package com.brekwol.daybreak

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.brekwol.daybreak.capture.CaptureScreen
import com.brekwol.daybreak.data.Photo
import com.brekwol.daybreak.designsystem.Daybreak
import com.brekwol.daybreak.designsystem.DaybreakTheme
import com.brekwol.daybreak.library.LibraryScreen
import com.brekwol.daybreak.settings.SettingsScreen
import com.brekwol.daybreak.viewer.ViewerScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { DaybreakApp() }
    }
}

private sealed interface Screen {
    data object Capture : Screen
    data object Library : Screen
    data class Viewer(val photo: Photo) : Screen
    data object Settings : Screen
}

@Composable
private fun DaybreakApp() {
    var stack by remember { mutableStateOf(listOf<Screen>(Screen.Capture)) }
    val push: (Screen) -> Unit = { stack = stack + it }
    val pop: () -> Unit = { if (stack.size > 1) stack = stack.dropLast(1) }

    BackHandler(enabled = stack.size > 1) { pop() }

    // Keeps each screen's saved state (camera mode, Pro values, library scroll)
    // while another screen is on top of it.
    val savedStates = rememberSaveableStateHolder()

    when (val screen = stack.last()) {
        Screen.Capture -> DaybreakTheme(darkroom = true) {
            SystemBars()
            savedStates.SaveableStateProvider("capture") {
                CaptureScreen(
                    onOpenLibrary = { push(Screen.Library) },
                    onOpenSettings = { push(Screen.Settings) },
                )
            }
        }
        Screen.Library -> DaybreakTheme {
            SystemBars()
            savedStates.SaveableStateProvider("library") {
                LibraryScreen(
                    onOpenCamera = pop,
                    onOpenPhoto = { push(Screen.Viewer(it)) },
                    onOpenSettings = { push(Screen.Settings) },
                )
            }
        }
        is Screen.Viewer -> DaybreakTheme(darkroom = true) {
            SystemBars()
            ViewerScreen(photo = screen.photo, onBack = pop)
        }
        Screen.Settings -> DaybreakTheme {
            SystemBars()
            SettingsScreen(onBack = pop)
        }
    }
}

/** Light or dark status and navigation bar icons to match the current theme. */
@Composable
private fun SystemBars() {
    val view = LocalView.current
    val dark = Daybreak.colors.isDark
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context.findActivity() ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
