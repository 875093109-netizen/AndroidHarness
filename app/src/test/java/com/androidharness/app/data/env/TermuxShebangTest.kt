package com.androidharness.app.data.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shebang repair for scripts shipped by Termux packages: language tooling
 * (pip, npm-cli.js, git helper scripts) must survive extraction with its
 * shebang rewritten into the deployed shell-tier prefix, while the
 * Android-bridge wrapper commands (pm, cmd, am, ...) are still dropped.
 */
class TermuxShebangTest {

    /** First line of Termux python-pip 26.x bin/pip3, verified against the repo. */
    private val pip3 = "#!/data/data/com.termux/files/usr/bin/python3.14"

    /** First line of Termux npm 11.x lib/node_modules/npm/bin/npm-cli.js. */
    private val npmCli = "#!/data/data/com.termux/files/usr/bin/env node"

    private val termuxSh = "#!/data/data/com.termux/files/usr/bin/sh"

    /** Stand-in for one build's deployed prefix. */
    private val deployed = LinuxEnvironmentManager.deployedBaseFor("com.example.app") + "/linux"

    @Test
    fun `python interpreter shebang rewrites into the deployed prefix`() {
        assertEquals(
            "#!${deployed}/bin/python3.14",
            TermuxShebangs.rewrittenFirstLine(pip3, deployed),
        )
    }

    @Test
    fun `env-style shebang uses bundled coreutils without requiring busybox applets`() {
        assertEquals(
            "#!${deployed}/bin/env node",
            TermuxShebangs.rewrittenFirstLine(npmCli, deployed),
        )
    }

    @Test
    fun `older deployed env shebangs are repaired without changing arguments`() {
        assertEquals("#!$deployed/bin/env node", TermuxShebangs.rewrittenFirstLine("#!$deployed/bin/applets/env node", deployed))
        assertEquals("#!$deployed/bin/env python3 -u", TermuxShebangs.rewrittenFirstLine("#!$deployed/bin/applets/env python3 -u", deployed))
        assertNull(TermuxShebangs.rewrittenFirstLine("#!$deployed/bin/env node", deployed))
        assertNull(TermuxShebangs.rewrittenFirstLine("#!/other/build/bin/applets/env node", deployed))
        assertNull(TermuxShebangs.rewrittenFirstLine("#!/system/bin/env node", deployed))
    }

    @Test
    fun `rewritten lines never reference the termux prefix`() {
        assertFalse(TermuxShebangs.rewrittenFirstLine(pip3, deployed)!!.contains("/data/data/com.termux"))
        assertFalse(TermuxShebangs.rewrittenFirstLine(npmCli, deployed)!!.contains("/data/data/com.termux"))
    }

    @Test
    fun `non-termux shebangs are left alone`() {
        assertNull(TermuxShebangs.rewrittenFirstLine("#!/usr/bin/env node", deployed))
        assertNull(TermuxShebangs.rewrittenFirstLine("#!/system/bin/sh", deployed))
        assertNull(TermuxShebangs.rewrittenFirstLine("", deployed))
    }

    @Test
    fun `termux paths outside files usr are not rewritable`() {
        assertNull(TermuxShebangs.rewrittenFirstLine("#!/data/data/com.termux/files/home/x", deployed))
    }

    @Test
    fun `android-bridge wrappers are classified for dropping`() {
        assertTrue(TermuxShebangs.isWrapperScript("pm", termuxSh))
        assertTrue(TermuxShebangs.isWrapperScript("settings", termuxSh))
        // same name but a language-tooling shebang is not a wrapper
        assertFalse(TermuxShebangs.isWrapperScript("pm", pip3))
        // pip is not a wrapper even with its real shebang
        assertFalse(TermuxShebangs.isWrapperScript("pip3", pip3))
        assertFalse(TermuxShebangs.isWrapperScript("npm", npmCli))
        // non-termux shebangs are never wrappers
        assertFalse(TermuxShebangs.isWrapperScript("pm", "#!/system/bin/sh"))
    }

    @Test
    fun `each build deploys into its own directory`() {
        // Two builds on one device must not untar over each other: the copy a
        // command execs has to be the one its environment was built for.
        assertEquals(
            "/data/local/tmp/androidharness/com.example.app",
            LinuxEnvironmentManager.deployedBaseFor("com.example.app"),
        )
        assertNotEquals(
            LinuxEnvironmentManager.deployedBaseFor("com.example.app"),
            LinuxEnvironmentManager.deployedBaseFor("com.example.app.debug"),
        )
    }
}
