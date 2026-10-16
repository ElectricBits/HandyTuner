// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.view.KeyEvent

/** The console UI's stepping rules, kept pure so [NavTest] can pin them. */
object Nav {
    /** ◀▶ on a choice, and L1/R1 between sections: one step, stopping at the ends (holding never wraps round). */
    fun step(i: Int, d: Int, n: Int): Int = if (n <= 0) -1 else (i + d).coerceIn(0, n - 1)

    /** A on a choice: the next option, wrapping, so A alone can reach every option. -1 (none picked) goes to the first. */
    fun next(i: Int, n: Int): Int = if (n <= 0) -1 else (i + 1).mod(n)

    /**
     * L1/R1 switch sections only when pressed on their own: the Quick Menu hotkey is both sticks + R1, and that
     * must never also flip the section. Armed on a lone shoulder press, cleared by any other key, fired on release.
     */
    class Shoulder {
        private var armed: Int? = null

        /** Every key-down. [othersHeld]: another key is already down. */
        fun down(code: Int, othersHeld: Boolean) {
            armed = if ((code == KeyEvent.KEYCODE_BUTTON_L1 || code == KeyEvent.KEYCODE_BUTTON_R1) && !othersHeld) code else null
        }

        /** Every key-up: the section step to take (-1, +1) or 0. */
        fun up(code: Int): Int {
            if (armed != code) return 0
            armed = null
            return if (code == KeyEvent.KEYCODE_BUTTON_R1) 1 else -1
        }
    }
}
