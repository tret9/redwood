package app.cash.redwood.treehouse

import android.util.Log

/**
 * Loads the bridge native library. Called once before any Zipline operations.
 *
 * On Android, the library is packaged into the APK via [externalNativeBuild]
 * (see [CMakeLists.txt](src/androidMain/cpp/CMakeLists.txt)).
 * It must be loaded after libhermesvmlean (loaded by Zipline's JsEngine) so Hermes
 * symbols are available for resolution.
 */
internal object BridgeLibraryLoader {
  private var loaded = false

  fun ensureLoaded() {
    if (loaded) return
    try {
      val oldPolicy = android.os.StrictMode.allowThreadDiskReads()
      // Load libhermesvmlean first so its symbols are available for libredwood-bridge
      System.loadLibrary("hermesvmlean")
      System.loadLibrary("redwood-bridge")

      // The bridge dispatch (rdmaChangeToJava) calls JNI FindClass for bridge-
      // annotated classes (e.g. ChildrenChange$Add). These classes pull in
      // kotlinx.serialization, whose transitive dependencies include
      // CoroutineExceptionHandlerImplKt. Its <clinit> calls ServiceLoader.load(),
      // which reads META-INF/services/ from the APK — a disk read. If this
      // happens on a thread with StrictMode enabled, the DiskReadViolation
      // marks the class as failed, and the next coroutine exception on main
      // crashes with NoClassDefFoundError.
      //
      // Preload the class now (while allowThreadDiskReads is still in effect)
      // so its <clinit> completes before StrictMode is re-enabled.
      try { Class.forName("kotlinx.coroutines.internal.CoroutineExceptionHandlerImplKt") } catch (_: Exception) {}

      android.os.StrictMode.setThreadPolicy(oldPolicy)
      loaded = true
      Log.i("BridgeLibraryLoader", "libredwood-bridge loaded successfully")
    } catch (e: UnsatisfiedLinkError) {
      Log.w("BridgeLibraryLoader", "libredwood-bridge not found — bridge dispatch unavailable", e)
    }
  }
}
