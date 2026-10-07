package com.kei.pulse.data

import com.kei.pulse.root.RootSupport

/**
 * Resolution / render-scale control via `wm size` + `wm density`, run as root through
 * PServer. Fully reversible (`wm size reset` / `wm density reset`) and cannot harm the
 * hardware — worst case the UI looks wrong until reset or reboot. Lowering resolution is a
 * real performance lever on the GPU (fewer pixels to render).
 */
class DisplayController {

    data class DisplaySpec(val width: Int, val height: Int, val density: Int)

    fun readNative(): DisplaySpec? {
        val sizeOut = RootSupport.runRootCommand("wm size") ?: return null
        val densOut = RootSupport.runRootCommand("wm density").orEmpty()
        val size = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(sizeOut) ?: return null
        val w = size.groupValues[1].toIntOrNull() ?: return null
        val h = size.groupValues[2].toIntOrNull() ?: return null
        val d = Regex("Physical density:\\s*(\\d+)").find(densOut)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return DisplaySpec(w, h, d)
    }

    /** percent >= 100 resets to native. Aspect ratio is preserved (both axes scaled). */
    fun applyScale(native: DisplaySpec, percent: Int): Boolean {
        if (percent >= 100) return reset()
        val w = (native.width.toLong() * percent / 100).toInt()
        val h = (native.height.toLong() * percent / 100).toInt()
        val d = if (native.density > 0) (native.density.toLong() * percent / 100).toInt() else 0
        val cmd = buildString {
            append("wm size ${w}x${h}")
            if (d > 0) append("; wm density $d")
        }
        RootSupport.runRootCommand(cmd)
        return true
    }

    fun reset(): Boolean {
        RootSupport.runRootCommand("wm size reset; wm density reset")
        return true
    }

    companion object {
        /** Offered render scales (percent of native). 100 = native. */
        val SCALES = listOf(100, 85, 75, 67, 50)
    }
}
