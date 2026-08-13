/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.web

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import com.vicinityprobe.service.WebServerService

class WebConsoleViewModel(application: Application) : AndroidViewModel(application) {
    fun isRunning(): Boolean = WebServerService.isRunning()

    fun start() {
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), WebServerService::class.java)
                .setAction(WebServerService.ACTION_START),
        )
    }

    fun stop() {
        getApplication<Application>().startService(
            Intent(getApplication(), WebServerService::class.java)
                .setAction(WebServerService.ACTION_STOP),
        )
    }
}
