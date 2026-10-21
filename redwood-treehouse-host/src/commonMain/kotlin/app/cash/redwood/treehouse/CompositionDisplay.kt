package app.cash.redwood.treehouse

import app.cash.redwood.widget.Widget
import kotlin.native.ObjCName

/**
 * Host containers for compositions mounted beside the root [TreehouseView].
 * Each id has its own widget children and survives a sibling composition failing.
 */
@ObjCName("CompositionDisplay", exact = true)
public interface CompositionDisplay<W : Any> {
  public fun children(compositionId: String): Widget.Children<W>
  public fun onFailed(compositionId: String, message: String)
}
