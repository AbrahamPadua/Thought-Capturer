package app.thoughtcapturer

import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Robolectric needs Java 21 for SDK 35+, and this PC builds with JDK 17
class SignInTest {
    private val ctx = RuntimeEnvironment.getApplication()
    private val p = ctx.prefs()

    private fun pendingCode(expiresInMs: Long = 60_000) {
        p.edit()
            .putString("device_code", "dc")
            .putString("user_code", "ABC")
            .putLong("code_expires", System.currentTimeMillis() + expiresInMs)
            .putLong("poll_interval", 1)
            .commit()
    }

    @Test
    fun keepsWaitingThroughPendingAndNetworkErrors() {
        pendingCode()
        val answers = mutableListOf<() -> JSONObject>(
            { throw HttpError(400, """{"error":"authorization_pending"}""") },
            { throw IOException("no network") },
            { JSONObject("""{"refresh_token":"rt"}""") },
        )
        SettingsActivity.waitForApproval(ctx) { answers.removeAt(0)() }
        assertEquals("rt", p.getString("refresh_token", null))
        assertNull(p.getString("device_code", null))
        assertNull(p.getString("user_code", null))
        assertNull(p.getString("signin_error", null))
    }

    @Test
    fun expiredCodeStopsWithoutAskingMicrosoft() {
        pendingCode(expiresInMs = -1)
        SettingsActivity.waitForApproval(ctx) { throw AssertionError("should not redeem an expired code") }
        assertEquals("The code expired. Tap Sign in again.", p.getString("signin_error", null))
        assertNull(p.getString("device_code", null))
    }

    @Test
    fun declinedSignInStopsWithTheError() {
        pendingCode()
        SettingsActivity.waitForApproval(ctx) { throw HttpError(400, """{"error":"authorization_declined"}""") }
        assertTrue(p.getString("signin_error", null)!!.startsWith("Sign-in failed: HTTP 400"))
        assertNull(p.getString("device_code", null))
        assertNull(p.getString("refresh_token", null))
    }
}
