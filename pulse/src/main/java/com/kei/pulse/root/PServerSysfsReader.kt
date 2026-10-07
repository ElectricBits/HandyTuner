package com.kei.pulse.root

import android.content.Context
import com.kei.pulse.data.PrivilegedSysfsReader

class PServerSysfsReader(
    private val context: Context,
) : PrivilegedSysfsReader {

    override fun readText(path: String): String? {
        val escapedPath = path.replace("'", "'\\''")
        return RootSupport.runRootCommand(
            command = "cat '$escapedPath' 2>/dev/null",
        )?.trim()?.takeIf { it.isNotEmpty() }
    }
}
