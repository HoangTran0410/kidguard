package dev.kidguard

import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cap -> Settings screen map lives once in [Intents] (it drifted when FixPerms, FeaturesSheet and SetupSteps each had one). */
class IntentsTest {
    @Test fun usageFallsBackToThePlainList() {
        assertEquals(listOf(Settings.ACTION_USAGE_ACCESS_SETTINGS to true, Settings.ACTION_USAGE_ACCESS_SETTINGS to false), Intents.capActions("usage"))
    }
    @Test fun everyCapHasAScreenAndUnknownGoesToAppInfo() {
        for (c in listOf("admin", "acc", "usage", "listener", "battery", "home", "install", "location", "notify", "mic"))
            assertTrue(c, Intents.capActions(c).isNotEmpty())
        assertEquals(listOf(Settings.ACTION_APPLICATION_DETAILS_SETTINGS to true), Intents.capActions("mic"))
    }
}
