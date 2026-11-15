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
     *
     * TDP and the other CPU/GPU paths gate on this rather than on [executeAsRoot] working, because the
     * Shizuku fallback runs as the shell user, which can't write those nodes.
     */
    val pServerAvailable: Boolean
        get() = binder != null && !ShizukuShell.forced() &&
            (works || (RootSupport.locked { pServer("echo ok") }.getOrNull() == "ok").also { if (it) works = true })

    private companion object {
        @Volatile var works = false
    }

    /**
     * PServer when it works, else Shizuku when HandyTuner may use it.
     * ponytail: where PServer is blocked every command re-asks it first (one refused binder call); cache the
     * "no" if that ever shows up in a profile.
     */
    fun executeAsRoot(cmd: String): Result<String?> = when {
        pServerAvailable -> pServer(cmd)
        ShizukuShell.ready() -> Result.success(ShizukuShell.run(cmd)?.takeIf { it != "null" })
        else -> Result.failure(IllegalStateException("PServer not available"))
    }

    private fun pServer(cmd: String): Result<String?> {
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
