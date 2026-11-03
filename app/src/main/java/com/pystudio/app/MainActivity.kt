package com.pystudio.app

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.core.content.FileProvider
import com.pystudio.app.state.AppViewModel
import com.pystudio.app.ui.AppRoot
import com.pystudio.app.ui.Host
import com.pystudio.app.ui.PyStudioTheme
import java.io.File

class MainActivity : ComponentActivity(), Host {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val mode = vm.prefs.theme
            val dark = when (mode) { "light" -> false; "system" -> isSystemInDarkTheme(); else -> true }
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            PyStudioTheme(mode) {
                Surface(color = MaterialTheme.colorScheme.background) { AppRoot(vm, this@MainActivity) }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        vm.onBackground()
    }

    override fun share(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Export project"))
        } catch (e: Exception) {
            vm.toast("Cannot share: ${e.message}")
        }
    }

    override fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { vm.toast("No app can open $url") }
    }

    override fun restart() {
        val i = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (i != null) startActivity(i)
        Runtime.getRuntime().exit(0)
    }
}
