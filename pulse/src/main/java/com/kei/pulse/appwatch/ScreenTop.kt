package com.kei.pulse.appwatch

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.kei.pulse.root.RootSupport

/**
 * The app on top of the TV, while one is connected (HandyTuner fork). Usage Access only says what opened last on
 * any screen, and docked the home app keeps reopening on the Odin's own screen, so it looked like the game had
 * ended. 20 ms through PServer.
 */
object ScreenTop {
    /** Read-only: lists each screen's top activity as one line (PServer replies break on multi-line output). */
    const val SCRIPT = "dumpsys activity activities | grep -E \"Display #|topResumedActivity\" | tr '\\n' ';'\n"

    fun tv(ctx: Context): Display? = ctx.getSystemService(DisplayManager::class.java)
        ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    /** The package on top of the TV; null without a TV, or when it can't be read. */
    fun onTv(ctx: Context): String? {
        val tv = tv(ctx) ?: return null
        return RootSupport.runGeneratedScript(ctx, "tops.sh", SCRIPT)?.let { parse(it, tv.displayId) }
    }

    /** From [SCRIPT]'s line: "Display #5 (…):;    topResumedActivity=ActivityRecord{… u0 pkg/.Activity} t218};…". */
    fun parse(out: String, displayId: Int): String? {
        val lines = out.split(';')
        val at = lines.indexOfFirst { it.trim().startsWith("Display #$displayId ") }.takeIf { it >= 0 } ?: return null
        val top = lines.drop(at + 1).firstOrNull { "Display #" in it || "topResumedActivity" in it } ?: return null
        return Regex("""u\d+ ([\w.]+)/""").find(top)?.groupValues?.get(1)
    }
}
