package app.cash.redwood.treehouse

import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.promise

class MountedCompositionDeliveryTest {
  @Test fun changesAndEventsStayOnTheirComposition() = CoroutineScope(Dispatchers.Default).promise {
    js(
      """
      if (typeof globalThis.__bridgeRegister !== 'function') {
        globalThis.__bridgeRegister = function () {}
      }
      """,
    )
    assertChangesAndEventsStayOnTheirComposition()
  }
}
