package com.pystudio.app

import com.chaquo.python.android.PyApplication

/** PyApplication starts the embedded CPython runtime (Chaquopy) before anything else. */
class App : PyApplication() {
    override fun onCreate() {
        super.onCreate()
        // JGit looks for ~/.gitconfig; point "home" at app-private storage.
        System.setProperty("user.home", filesDir.absolutePath)
    }
}
