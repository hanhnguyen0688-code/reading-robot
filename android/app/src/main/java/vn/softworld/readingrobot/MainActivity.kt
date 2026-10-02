package vn.softworld.readingrobot

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import vn.softworld.readingrobot.session.ReadingViewModel
import vn.softworld.readingrobot.ui.Dim
import vn.softworld.readingrobot.ui.KioskRoot
import vn.softworld.readingrobot.ui.LocalDim
import vn.softworld.readingrobot.ui.PinDialog
import vn.softworld.readingrobot.ui.StageBackground
import vn.softworld.readingrobot.ui.TeacherColors
import vn.softworld.readingrobot.ui.TeacherScreen

class MainActivity : ComponentActivity() {
    val vm: ReadingViewModel by viewModels()
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) askMic.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            var askPin by remember { mutableStateOf(false) }
            var teacher by remember { mutableStateOf(false) }
            BackHandler { if (teacher) teacher = false else vm.goHome() }
            if (teacher) {
                MaterialTheme(colorScheme = TeacherColors) { TeacherScreen(vm, ui.dataVersion) { teacher = false; vm.bumpData() } }
            } else {
                StageBackground {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = maxWidth.value; val h = maxHeight.value
                        val portrait = h > w * 1.15f
                        val u = if (portrait) minOf(w / 1080f, h / 1920f) else minOf(w / 1920f, h / 1240f)
                        CompositionLocalProvider(LocalDim provides Dim(u, LocalDensity.current.fontScale, portrait)) {
                            MaterialTheme { KioskRoot(vm, ui) { askPin = true } }
                        }
                    }
                }
                if (askPin) MaterialTheme(colorScheme = TeacherColors) {
                    PinDialog(vm.store.settings.teacherPin, onOk = { askPin = false; teacher = true }, onCancel = { askPin = false })
                }
            }
        }
    }
}
