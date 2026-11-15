package com.kei.pulse.root

import android.annotation.SuppressLint
import android.os.IBinder
import android.os.Parcel
import java.nio.charset.Charset

@SuppressLint("DiscouragedPrivateApi", "PrivateApi")
class RootExec {

    private val binder: IBinder? = runCatching {
        val serviceManager = Class.forName("android.os.ServiceManager")
        val getService = serviceManager.getDeclaredMethod("getService", String::class.java)
        getService.invoke(serviceManager, "PServerBinder") as IBinder
    }.getOrDefault(null)

    /**
     * Whether PServer really runs our commands, not just whether it's registered: on the Odin 2 Mini
     * the lookup succeeds but SELinux blocks the call (HandyTuner issue #4). A yes is kept for the
     * process; a no is asked again next time, since pservice can come up after we do.
     */
    val pServerAvailable: Boolean
        get() = binder != null &&
            (works || (RootSupport.runRootCommand("echo ok") == "ok").also { if (it) works = true })

    private companion object {
        @Volatile var works = false
    }

    fun executeAsRoot(cmd: String): Result<String?> {
        if (binder == null) return Result.failure(IllegalStateException("PServer not available"))

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeStringArray(arrayOf(cmd, "1"))
            binder.transact(0, data, reply, 0)
            Result.success(decodeReply(reply))
        } catch (throwable: Throwable) {
            Result.failure(throwable)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun decodeReply(reply: Parcel): String? {
        return reply.createByteArray()
            ?.toString(Charset.defaultCharset())
            ?.trim()
            ?.let { value -> if (value == "null") null else value }
    }
}
