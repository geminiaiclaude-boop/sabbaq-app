package app.sabbaq.fleet

import app.sabbaq.core.AppRelease
import app.sabbaq.core.UpdateState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The update notification: once per published version, again and again while the update is required. */
class UpdateNotifierTest {
    private val release = AppRelease(latestCode = 200, latestName = "2.0.4", minCode = 0)

    @Test fun a_new_version_is_announced_once() {
        assertEquals(UpdateState.AVAILABLE, UpdateNotifier.decide(release, installedCode = 100, notifiedCode = 0))
        assertNull(UpdateNotifier.decide(release, installedCode = 100, notifiedCode = 200))
        assertEquals(UpdateState.AVAILABLE, UpdateNotifier.decide(release.copy(latestCode = 300), installedCode = 100, notifiedCode = 200))
    }

    @Test fun nothing_when_up_to_date() {
        assertNull(UpdateNotifier.decide(release, installedCode = 200, notifiedCode = 0))
        assertNull(UpdateNotifier.decide(AppRelease(), installedCode = 100, notifiedCode = 0))
    }

    @Test fun a_required_update_is_announced_on_every_check() {
        val required = release.copy(minCode = 150)
        assertEquals(UpdateState.REQUIRED, UpdateNotifier.decide(required, installedCode = 100, notifiedCode = 200))
    }
}
