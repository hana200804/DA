package com.signa.app.ui.drive

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.signa.app.R
import com.signa.app.ui.theme.SignaTheme
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoReplayTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    @SdkSuppress(minSdkVersion = 28)
    fun replaysFileAndDrawsConfirmedSign() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = compose.activity
        val clip = File(context.cacheDir, "vision-replay.mp4")
        try {
            instrumentation.context.assets.open(clip.name).use { input ->
                clip.outputStream().use { output -> input.copyTo(output) }
            }
            val picker = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = object : ActivityResultRegistry() {
                    override fun <I, O> onLaunch(
                        requestCode: Int,
                        contract: ActivityResultContract<I, O>,
                        input: I,
                        options: ActivityOptionsCompat?
                    ) {
                        // Đi qua callback chọn tệp của màn phát lại với video fixture.
                        dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(Uri.fromFile(clip)))
                    }
                }
            }
            compose.setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides picker) {
                    SignaTheme { VideoReplayRoute(onBack = {}) }
                }
            }
            compose.onNodeWithText(context.getString(R.string.video_pick)).performClick()
            val completed = context.getString(R.string.video_frame_count, 48)
            compose.waitUntil(timeoutMillis = 150_000) {
                compose.onAllNodesWithText(completed).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("P.130", substring = true).assertIsDisplayed()
            compose.onNodeWithText("FPS", substring = true).assertIsDisplayed()
            if (InstrumentationRegistry.getArguments().getString("signaReplayScreenshot") == "true") {
                val command = "screencap -p /sdcard/Download/signa-replay.png"
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                    .use { it.readBytes() }
            }
        } finally {
            clip.delete()
        }
    }
}
