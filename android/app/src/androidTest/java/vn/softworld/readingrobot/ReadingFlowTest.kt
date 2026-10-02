package vn.softworld.readingrobot

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import vn.softworld.readingrobot.session.Screen

/**
 * Runs the whole Reading Robot flow on a real Android (emulator): home -> greet -> read -> help -> finish -> teacher report.
 * The child's voice is injected through the same callback the speech engines use; screenshots go to /sdcard/Download/rr-*.png.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReadingFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(GrantPermissionRule.grant(android.Manifest.permission.RECORD_AUDIO)).around(compose)

    private fun shot(name: String) {
        compose.waitForIdle(); Thread.sleep(600)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/Download/rr-$name.png").close()
        Thread.sleep(800)
    }

    private fun speak(text: String) {
        compose.runOnUiThread { compose.activity.vm.onFinal(text, null) }
        compose.waitForIdle(); Thread.sleep(400)
    }

    @Test fun fullReadingSession() {
        val vm = compose.activity.vm
        compose.runOnUiThread { vm.store.resetAll(); vm.bumpData() }
        compose.waitUntilAtLeastOneExists(hasText("Who's reading today?"), 15_000)
        shot("1-home")

        compose.onNodeWithText("Cathy").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Hi Cathy!"), 10_000)
        shot("2-greet")

        compose.onNodeWithText("\"Ready!\"").performClick()
        compose.waitUntil(30_000) { vm.ui.value.screen == Screen.READING && vm.ui.value.progress != null }
        shot("3-passage")

        listOf("Milo the cat had a big problem", "His fav favourite red sock was gone", "He looked under the bed",
            "He looked behind the door", "He even looked inside the fridge which was cold", "Then Milo heard a tiny speak",
            "A moose mouse was sleeping in the sock").forEach { speak(it) }
        shot("4-reading")

        speak("help")
        compose.waitUntil(20_000) { vm.ui.value.progress?.marks?.getOrNull(46) == 't' && !vm.ui.value.talking }
        Thread.sleep(800)
        speak("snoring like a little train")

        compose.waitUntil(30_000) { vm.ui.value.screen == Screen.FINISH }
        Thread.sleep(1500)
        shot("5-finish")
        val r = vm.store.reports.first()
        assertEquals(51, r.totalWords)
        assertEquals(3, r.errors)
        assertEquals(94.1, r.accuracyPct, 0.001)

        compose.onNodeWithText("Wave bye-bye!").performClick()
        compose.waitUntil(15_000) { vm.ui.value.screen == Screen.HOME }
        compose.onNodeWithText("Teacher").performClick()
        compose.waitUntilAtLeastOneExists(hasText("Enter the teacher PIN"), 5_000)
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("1234")
        compose.waitUntilAtLeastOneExists(hasText("Teacher note".uppercase()), 10_000)
        shot("6-teacher")
    }
}
