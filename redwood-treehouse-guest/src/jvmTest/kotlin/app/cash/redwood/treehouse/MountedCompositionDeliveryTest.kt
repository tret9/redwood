package app.cash.redwood.treehouse

import kotlin.test.Test
import kotlinx.coroutines.runBlocking

class MountedCompositionDeliveryTest {
  @Test fun changesAndEventsStayOnTheirComposition() = runBlocking {
    assertChangesAndEventsStayOnTheirComposition()
  }
}
