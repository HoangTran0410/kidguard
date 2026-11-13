package dev.kidguard

import org.junit.Assert.*
import org.junit.Test

class SelfSetupLogicTest {
    @Test fun noAccounts() = assertTrue(SelfSetupLogic.accounts("Accounts: 0\n  User UserInfo{0:Owner:13}:\n  Accounts: 0\n").isEmpty())

    @Test fun listsAccounts() {
        val d = "  Accounts: 1\n    Account {name=kid@gmail.com, type=com.google}\n"
        assertEquals(listOf("kid@gmail.com (com.google)"), SelfSetupLogic.accounts(d))
    }

    // shape of `dumpsys account` on Android 14 (emulator), with a Google, a Xiaomi and a third-party (Zalo-like) account
    private val DUMP = """
User UserInfo{0:Owner:4c13}:
  Accounts: 3
    Account {name=kid@gmail.com, type=com.google}
    Account {name=kid, type=com.xiaomi}
    Account {name=0912345678, type=com.zing.zalo.account}

  AccountId, Action_Type, timestamp, UID, TableName, Key
  Accounts History
  1,action_account_add,2026-11-13 15:13:01,10141,accounts,0

  Active Sessions: 1
    Session: expectLaunch false, connected false, stats (0/0/0), lifetime 0.36, hasFeatures, Account {name=0912345678, type=com.zing.zalo.account}, android.account.DEVICE_OR_PROFILE_OWNER_DISALLOWED

  RegisteredServicesCache: 3 services
    ServiceInfo: AuthenticatorDescription {type=com.google}, ComponentInfo{com.google.android.gms/com.google.android.gms.auth.GetToken}, uid 10150
    ServiceInfo: AuthenticatorDescription {type=com.xiaomi}, ComponentInfo{com.xiaomi.account/com.xiaomi.account.XMAuthenticatorService}, uid 1000
    ServiceInfo: AuthenticatorDescription {type=com.zing.zalo.account}, ComponentInfo{com.zing.zalo/com.zing.zalo.auth.AuthService}, uid 10141

  Account visibility:
"""
    private val THIRD = SelfSetupLogic.packages("package:com.zing.zalo\npackage:t.acct\n")

    @Test fun authenticatorsByType() {
        val a = SelfSetupLogic.authenticators(DUMP)
        assertEquals("com.google.android.gms", a["com.google"]); assertEquals("com.zing.zalo", a["com.zing.zalo.account"])
    }

    @Test fun planSplitsSystemAndAppAccounts() {
        val p = SelfSetupLogic.planAccounts(DUMP, THIRD)
        assertEquals(listOf("com.zing.zalo"), p.disable)
        assertEquals(listOf("0912345678 (com.zing.zalo.account)"), p.keep)
        assertEquals(listOf("kid@gmail.com (com.google)", "kid (com.xiaomi)"), p.remove)
    }

    @Test fun planOnlyAppAccounts() {
        val d = "  Accounts: 1\n    Account {name=tester, type=t.acct.type}\n  RegisteredServicesCache: 1 services\n" +
            "    ServiceInfo: AuthenticatorDescription {type=t.acct.type}, ComponentInfo{t.acct/t.acct.Svc}, uid 10141\n"
        val p = SelfSetupLogic.planAccounts(d, THIRD)
        assertEquals(listOf("t.acct"), p.disable); assertTrue(p.remove.isEmpty())
    }

    @Test fun planIgnoresHiddenAccount() {   // authenticator already off: not in the cache, the account does not count
        val d = "  Accounts: 1\n    Account {name=tester, type=t.acct.type}\n  RegisteredServicesCache: 0 services\n"
        val p = SelfSetupLogic.planAccounts(d, THIRD)
        assertTrue(p.disable.isEmpty() && p.remove.isEmpty())
    }

    @Test fun planNoAccounts() = assertEquals(SelfSetupLogic.AccountPlan(emptyList(), emptyList(), emptyList()), SelfSetupLogic.planAccounts("  Accounts: 0\n", THIRD))

    @Test fun packagesParse() = assertEquals(setOf("a.b", "c.d"), SelfSetupLogic.packages("package:a.b\r\npackage:c.d\n\nWarning: x\n"))

    @Test fun pendingRoundTrip() {
        val p = SelfSetupLogic.Pending(1000L, true, listOf("com.zing.zalo", "t.acct"))
        assertEquals(p, SelfSetupLogic.decodePending(SelfSetupLogic.encodePending(p)))
        assertNull(SelfSetupLogic.decodePending("")); assertNull(SelfSetupLogic.decodePending("x|1|a"))
        assertEquals(listOf("ok.pkg"), SelfSetupLogic.decodePending("5|0|ok.pkg,bad;rm")!!.pkgs)
    }

    @Test fun pendingStaleAfterTenMinutes() {
        val p = SelfSetupLogic.Pending(1_000_000L, false, listOf("a.b"))
        assertFalse(SelfSetupLogic.pendingStale(p, 1_000_000L + 9 * 60_000L))
        assertTrue(SelfSetupLogic.pendingStale(p, 1_000_000L + 11 * 60_000L))
        assertTrue(SelfSetupLogic.pendingStale(p, 999_000L))   // clock went back
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

    @Test fun colorOsPermissionMonitoring() {
        // real output from an Oppo CPH1912 (ColorOS 5, Android 8.1) with permission monitoring on
        assertTrue(SelfSetupLogic.permMonitoring("Security exception: uid 2000 does not have android.permission.UPDATE_APP_OPS_STATS."))
        assertTrue(SelfSetupLogic.permMonitoring("Operation not allowed: java.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current process has android.permission.GRANT_RUNTIME_PERMISSIONS."))
        assertTrue(!SelfSetupLogic.permMonitoring("Error: Unknown package: dev.kidguard"))
    }
}
