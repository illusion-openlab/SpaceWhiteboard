package tech.illusion.spacewhiteboard.platform

import android.content.pm.PackageManager
import android.os.Bundle
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

private const val HAND_TRACKING_PERMISSION = "com.picovr.permission.HAND_TRACKING"
private const val HAND_TRACKING_REQUEST_CODE = 1001

/**
 * `com.picovr.permission.HAND_TRACKING` is a **runtime** permission on PICO OS, so declaring it
 * in the manifest is necessary but **not sufficient** — without an explicit request it stays
 * ungranted and the SDK fails completely silently (no crash, no error, no log).
 *
 * Note: this app's input goes through SpatialUI's `detectSpatialDragGesture` pointer pipeline,
 * not a `*TrackingProvider` directly, so whether this permission is actually required is
 * unverified. Every prior sibling project declares it; the cost of doing so is negligible.
 *
 * No result callback is needed: the SDK auto-recovers once the permission is granted.
 */
class LaunchActivity : SpatialLaunchActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(HAND_TRACKING_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(HAND_TRACKING_PERMISSION), HAND_TRACKING_REQUEST_CODE)
        }
    }
}
