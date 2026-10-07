// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.Properties

/**
 * Writes to a temp file and renames it over the target, so a crash or low-memory kill mid-write
 * leaves the old file, never half of one. The temp name is unique because the app and
 * :overlay processes write some of the same files.
 */
fun File.writeAtomic(bytes: ByteArray) {
    val tmp = File(parentFile, "$name.${System.nanoTime()}.tmp")
    tmp.writeBytes(bytes)
    if (!tmp.renameTo(this)) { tmp.delete(); error("couldn't replace $name") }
}

fun File.writeAtomic(text: String) = writeAtomic(text.toByteArray())

/** Same bytes as Properties.store(OutputStream), which the readers load() back. */
fun File.storeAtomic(p: Properties) = writeAtomic(ByteArrayOutputStream().also { p.store(it, null) }.toByteArray())

/**
 * An exclusive lock on a data file, visible to the other HandyTuner process.
 *
 * The app and `:overlay` both write `games.json` and `originals.properties`, and `@Synchronized` only
 * covers one process at a time, so a read-modify-write could interleave and drop an entry. The atomic
 * rename above stops a *torn* file but not a *lost write*: the loser's whole file replaces the
 * winner's. Java's advisory lock is honoured by both processes, and the kernel releases it if a
 * process dies, so a crash can't leave it stuck (docs/BUGS.md #10).
 *
 * The lock lives on a separate `<name>.lock` file, never on the data file itself, because a rename
 * replaces the inode: a lock held on the old file would guard nothing once the rename lands, and the
 * next process would open the new file unheld. The lock file is a few empty bytes and is left in
 * place on purpose — deleting it would race with a process waiting on it.
 *
 * Only read-modify-write needs this. A plain reader needs nothing: rename is atomic, so it sees
 * either the whole old file or the whole new one.
 */
object FileGuard {
    /** Re-entrant within a thread: [GameStore.set] reads through [GameStore.all], which locks too. */
    private val held = ThreadLocal<Boolean>()

    fun <T> locked(file: File, body: () -> T): T {
        if (held.get() == true) return body()
        val lf = File(file.parentFile, "${file.name}.lock")
        lf.parentFile?.mkdirs()
        return RandomAccessFile(lf, "rw").use { raf ->
            raf.channel.lock().use {
                held.set(true)
                try { body() } finally { held.remove() }
            }
        }
    }
}
