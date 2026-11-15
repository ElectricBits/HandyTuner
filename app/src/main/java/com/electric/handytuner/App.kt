// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.app.Application
import com.kei.pulse.root.ShizukuShell

/**
 * Shizuku sends its binder to the provider, which lives in the main process. The :overlay and :pulse
 * processes run commands too: the main process passes the binder on, and [ShizukuShell] has the others
 * ask for it, but only once PServer has failed them, so on an Odin where PServer works nothing changes.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ShizukuShell.init(this, providerProcess = getProcessName() == packageName)
    }
}
