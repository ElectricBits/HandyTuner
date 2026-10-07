package com.kei.pulse.data

fun interface PrivilegedSysfsReader {
    fun readText(path: String): String?
}
