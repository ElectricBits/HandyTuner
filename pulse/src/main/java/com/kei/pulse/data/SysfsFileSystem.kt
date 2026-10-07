package com.kei.pulse.data

import java.io.File

interface SysfsFileSystem {
    fun listPolicyDirectories(root: String): List<String>
}

class RealSysfsFileSystem : SysfsFileSystem {
    override fun listPolicyDirectories(root: String): List<String> {
        val directory = File(root)
        return directory.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("policy") }
            ?.map { it.absolutePath }
            .orEmpty()
    }
}
