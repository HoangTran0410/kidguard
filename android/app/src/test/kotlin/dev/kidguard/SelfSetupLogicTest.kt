package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class SelfSetupLogicTest {
    @Test fun noAccounts() = assertTrue(SelfSetupLogic.accounts("Accounts: 0\n  User UserInfo{0:Owner:13}:\n  Accounts: 0\n").isEmpty())

    @Test fun listsAccounts() {
        val d = "  Accounts: 1\n    Account {name=kid@gmail.com, type=com.google}\n"
        assertEquals(listOf("kid@gmail.com (com.google)"), SelfSetupLogic.accounts(d))
    }

    @Test fun accessibilityAppend() {
        val e = "dev.kidguard/dev.kidguard.Acc"
        assertEquals(e, SelfSetupLogic.withAccessibility("null", e))
        assertEquals(e, SelfSetupLogic.withAccessibility("", e))
        assertEquals("a/b:$e", SelfSetupLogic.withAccessibility("a/b", e))
        assertNull(SelfSetupLogic.withAccessibility("a/b:$e", e))
    }

    @Test fun okDetection() {
        assertTrue(SelfSetupLogic.ok(""))
        assertFalse(SelfSetupLogic.ok("java.lang.SecurityException: denied"))
    }
}
