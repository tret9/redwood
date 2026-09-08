package app.cash.redwood.treehouse

import app.cash.redwood.protocol.RedwoodVersion
import app.cash.redwood.protocol.guest.GuestProtocolAdapter
import app.cash.redwood.protocol.guest.ProtocolMismatchHandler
import app.cash.redwood.protocol.guest.ProtocolWidgetSystemFactory
import kotlinx.serialization.json.Json

internal expect fun BridgeGuestProtocolAdapter(
  json: Json,
  hostVersion: RedwoodVersion,
  widgetSystemFactory: ProtocolWidgetSystemFactory,
  mismatchHandler: ProtocolMismatchHandler,
): GuestProtocolAdapter
