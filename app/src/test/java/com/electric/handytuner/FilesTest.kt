// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import java.io.File
import java.util.Properties
import org.junit.Assert.assertEquals
import org.junit.Test

class FilesTest {
    @Test fun replacesAndLeavesNoTempFiles() {
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val f = File(dir, "x.properties")
        f.writeAtomic("old")
        f.storeAtomic(Properties().apply { setProperty("app.gamenative:SlimeRancher.exe", "LOW_PING") })
        assertEquals("LOW_PING", Properties().apply { f.inputStream().use { load(it) } }.getProperty("app.gamenative:SlimeRancher.exe"))
        assertEquals(listOf("x.properties"), dir.list()!!.toList())
    }
}
