package com.kei.pulse.root

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RootCommandRunner(
    private val context: Context,
    private val rootExec: RootExec = RootExec(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    val isAvailable: Boolean
        get() = rootExec.pServerAvailable

    suspend fun executeScript(script: String): Result<String?> = withContext(dispatcher) {
        runCatching {
            RootSupport.runGeneratedScript(
                context = context,
                scriptName = "apply-frequencies.sh",
                scriptContents = script,
            )
        }
    }
}
