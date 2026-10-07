// HandyTuner — Copyright (C) 2026 ElectricBits
// SPDX-License-Identifier: GPL-2.0-only
package com.electric.handytuner

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Grants] is the one table both the setup guide and Diagnostics read, so a row that is missing or empty
 * shows up as a blank button or a dead end on the device. Cheap to keep honest.
 */
class GrantsTest {
    @Test fun `every permission has a row, so byKind can't come up empty`() {
        Grants.Kind.entries.forEach { k ->
            val g = Grants.byKind(k)
            assertTrue("$k has no title", g.title.isNotBlank())
            assertTrue("$k says nothing about why it's needed", g.why.isNotBlank())
            assertTrue("$k has no button label", g.button.isNotBlank())
            assertTrue("$k tells HandyHelper nothing to say", g.steps.isNotEmpty())
        }
        assertEquals(Grants.Kind.entries.size, Grants.all.size)
    }

    @Test fun `the guide asks for them in the order the setup flow uses`() {
        // Accessibility first (nothing else works without it), then usage, then notifications.
        assertEquals(
            listOf(Grants.Kind.ACCESSIBILITY, Grants.Kind.USAGE, Grants.Kind.NOTIFICATIONS),
            Grants.all.map { it.kind },
        )
    }

    @Test fun `each permission names the page it opens`() {
        // The path is the words a person reads on screen; an empty one is a step that teaches nothing.
        Grants.all.forEach { assertTrue("${it.kind} has no path", it.path.isNotBlank()) }
    }

    @Test fun `only accessibility needs the way round a greyed-out switch`() {
        // Usage access and notifications have no restricted-settings wall, so an extra button there would
        // just be a confusing extra thing to press.
        assertTrue(Grants.escapeSteps.isNotEmpty())
        assertEquals("Allow notifications", Grants.byKind(Grants.Kind.NOTIFICATIONS).button)
    }

    @Test fun `an enabled service is recognised in both formats Android writes`() {
        // On the Odin (2026-10-01) Android wrote the full class name while the check only ever compared
        // against the short form, so a granted overlay still read as "not granted" and setup never moved on.
        assertTrue(Grants.serviceMatches(
            "com.electric.handytuner/com.electric.handytuner.OverlayService", "com.electric.handytuner", ".OverlayService"))
        assertTrue(Grants.serviceMatches(
            "com.electric.handytuner/.OverlayService", "com.electric.handytuner", ".OverlayService"))
        // Also usable with the manifest's full class name.
        assertTrue(Grants.serviceMatches(
            "com.electric.handytuner/com.electric.handytuner.OverlayService",
            "com.electric.handytuner", "com.electric.handytuner.OverlayService"))
    }

    @Test fun `another app's service is not ours, however it is written`() {
        val pkg = "com.electric.handytuner"
        listOf(
            "com.electric.portaltuner/com.electric.portaltuner.Watcher",
            "com.electric.portaltuner/.Watcher",
            "de.langerhans.odintools/de.langerhans.odintools.service.ForegroundAppWatcherService",
            "com.electric.handytuner/.SomeOtherService",   // our package, someone else's service
            "com.electric.handytuner.OverlayService",       // no package separator at all
            "",
        ).forEach { assertFalse("matched $it", Grants.serviceMatches(it, pkg, Grants.A11Y_SERVICE)) }
    }

    // BUGS.md #18: the plain check answered ALLOWED while the package's own mode was `ignore`.
    @Test fun `usage access needs an explicit allow`() {
        assertTrue(Grants.usageModeGrants(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_ALLOWED))
        // The uid entry said allow; the package's own mode says ignore, and the OS enforces ignore.
        assertFalse(Grants.usageModeGrants(AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ALLOWED))
        assertFalse(Grants.usageModeGrants(AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_ALLOWED))
        // Nobody set anything: fall back to what the platform reports.
        assertTrue(Grants.usageModeGrants(AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_ALLOWED))
        assertFalse(Grants.usageModeGrants(AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_IGNORED))
    }

    @Test fun odinAssistantIsDroppedAndOursKept() {
        val ours = "com.electric.handytuner/com.electric.handytuner.OverlayService"
        val odin = "com.odin.gameassistant/com.ro.gameassistant.service.ForegroundAppMonitorV4Service"
        assertEquals(ours, Grants.withoutOdinAssistant("$ours:$odin"))
        assertEquals(ours, Grants.withoutOdinAssistant("$odin:$ours"))
        assertEquals("", Grants.withoutOdinAssistant(odin))
        assertNull(Grants.withoutOdinAssistant(ours))   // nothing to change: no write
        assertNull(Grants.withoutOdinAssistant(""))
    }
}
