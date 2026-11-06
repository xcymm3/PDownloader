package com.pdownloader.browser

import android.app.Application
import com.pdownloader.browser.download.DownloadRepository

class BrowserApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DownloadRepository.get(this).recoverInterrupted()
        DebugFixtures.start(this)
    }
}
