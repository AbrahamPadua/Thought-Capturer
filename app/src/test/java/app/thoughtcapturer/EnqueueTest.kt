package app.thoughtcapturer

import android.Manifest
import android.content.pm.PackageManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34]) // Robolectric needs Java 21 for SDK 35+, and this PC builds with JDK 17
class EnqueueTest {
    private val ctx = RuntimeEnvironment.getApplication()

    // without it, Android 14+ throws on schedule() and every capture falls back to the clipboard
    @Test
    fun declaresNetworkStatePermission() {
        val perms = ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertTrue(perms.joinToString(), Manifest.permission.ACCESS_NETWORK_STATE in perms)
    }
}
