package com.kei.pulse.root

import org.junit.Assert.assertEquals
import org.junit.Test

/** Shizuku's shell user can't read HandyTuner's files, so `sh <script>` has to arrive as the script's text. */
class ShizukuShellTest {
    private val files = mapOf("/data/user/0/x/files/k.sh" to "echo \"$1 $2\"\nexit 0\n")
    private fun stdin(cmd: String) = ShizukuShell.stdin(cmd) { files[it] }

    @Test fun plainCommandUnchanged() = assertEquals("settings get system fan_mode", stdin("settings get system fan_mode"))

    @Test fun scriptBecomesHereDocument() = assertEquals(
        "sh -s <<'__HT_EOF'\necho \"$1 $2\"\nexit 0\n__HT_EOF",
        stdin("sh /data/user/0/x/files/k.sh"))

    @Test fun argumentsStayWithSh() = assertEquals(
        "sh -s none. 3 <<'__HT_EOF'\necho \"$1 $2\"\nexit 0\n__HT_EOF",
        stdin("sh /data/user/0/x/files/k.sh none. 3"))

    // PServer.ok appends this; it must come after the here-document marker, on the same line.
    @Test fun operatorsAfterTheMarker() = assertEquals(
        "sh -s a <<'__HT_EOF' && echo __ht_ok\necho \"$1 $2\"\nexit 0\n__HT_EOF",
        stdin("sh /data/user/0/x/files/k.sh a && echo __ht_ok"))

    @Test fun unreadableScriptLeftAlone() = assertEquals("sh /nope.sh", stdin("sh /nope.sh"))
}
