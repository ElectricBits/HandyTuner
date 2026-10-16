// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

object About {
    /** The only device HandyTuner has been tested on (Build.MODEL). */
    const val TESTED_MODEL = "Odin2 Portal"
    val untestedDevice get() = android.os.Build.MODEL != TESTED_MODEL

    const val SOURCE = "https://github.com/ElectricBits/HandyTuner"
    /** The owner's Ko-fi page (empty = no donate button). */
    const val KOFI = "https://ko-fi.com/electricbits"
    /** The community Discord invite (empty = no button). */
    const val DISCORD = "https://discord.gg/uGQQ2n36QR"
}
