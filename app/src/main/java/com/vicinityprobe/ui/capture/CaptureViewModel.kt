/*
 * VicinityProbe — professional environmental measurement system
 *
 * Copyright (C) 2026 Verlintas
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.vicinityprobe.ui.capture

import android.app.Application
import android.content.Intent
import android.net.VpnService
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vicinityprobe.service.CaptureController
import com.vicinityprobe.service.CaptureStats
import com.vicinityprobe.service.PacketCaptureService
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class CaptureViewModel(application: Application) : AndroidViewModel(application) {
    val stats: StateFlow<CaptureStats> = CaptureController.stats.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(3000), CaptureController.stats.value,
    )

    fun prepareIntent(): Intent? = VpnService.prepare(getApplication())

    fun start() {
        getApplication<Application>().startForegroundService(
            Intent(getApplication(), PacketCaptureService::class.java)
                .setAction(PacketCaptureService.ACTION_START),
        )
    }

    fun stop() {
        getApplication<Application>().startService(
            Intent(getApplication(), PacketCaptureService::class.java)
                .setAction(PacketCaptureService.ACTION_STOP),
        )
    }
}
