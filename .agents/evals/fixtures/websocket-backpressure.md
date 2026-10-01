Task — websocket frames are dropped under load and nobody can tell why.

The fix needs all of: a new `onBackpressure` callback on the `WebSocketSession` SPI contract
(observable, and `AbstractWebSocketSessionTck` says nothing about it); a change in the Community
binding's write path, which allocates a fresh heap buffer per frame; a JFR event so the drop is
visible at all; and `docs/subsystems/` has no websocket page to update, because none exists.
