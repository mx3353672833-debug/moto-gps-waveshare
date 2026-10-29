package top.maler.motogps

import android.app.Application
import top.maler.motogps.app.AppModel

class MotoApplication : Application() {
    /** Process-wide ride state; created lazily on the main thread. */
    val model: AppModel by lazy { AppModel(this) }
}
