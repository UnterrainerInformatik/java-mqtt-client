## Why

The Paho `MqttClient` we wrap (`MqttClient.java:51`) is the synchronous flavor: every subscription callback is dispatched on a single internal thread (`MQTT Call: <clientId>`). Our typed `subscribe(topic, type, setter)` invokes `setter.accept(...)` directly on that thread (`MqttClient.java:177`), so any consumer whose setter blocks — locks, HTTP calls, logging I/O — stalls the dispatcher and back-pressures every other subscription on the same client. A downstream consumer recently observed a ~60 s silence followed by a burst-drain caused exactly by this: a 3 s per-message `mutateState` lock cascading across queued messages on the single Paho thread. The library should not make a slow consumer block the entire MQTT pipeline.

## What Changes

- **BREAKING (internal)**: replace the synchronous `org.eclipse.paho.client.mqttv3.MqttClient` with `MqttAsyncClient` inside `MqttClient.java`. Public API of `info.unterrainer.commons.mqttclient.MqttClient` (constructors, `connect`, `send`, `subscribe`, `unsubscribe`, `disconnect`) stays source-compatible.
- Add a bounded dispatch executor owned by `MqttClient`. Every `IMqttMessageListener` registered through `subscribe(...)` is wrapped so that the user's listener runs on the dispatch executor, not on the Paho I/O thread.
- Document and bound the back-pressure behavior when the dispatch executor is saturated (configurable policy: drop-oldest, drop-newest, or block — default: drop-oldest with a WARN log so loss is visible).
- Make the executor sizing and queue capacity configurable through `MqttClientConfiguration` (with sensible defaults) and overloaded `MqttClient` constructors.
- Shut the executor down cleanly on `disconnect()`; drain in-flight handlers within a bounded timeout.
- Keep `connect()` semantics observable: where the sync client returned only after CONNACK, the async equivalent now `waits` on the connect token up to the existing 10 s timeout so callers see no behavior change.
- Add tests that prove a slow listener on one topic does not delay messages on another topic on the same client.

## Capabilities

### New Capabilities
- `mqtt-dispatch`: non-blocking dispatch of MQTT subscription callbacks. Owns the bounded executor, the wrapping of user listeners, the saturation policy, and the lifecycle (start with the client, drain on disconnect).

### Modified Capabilities
<!-- None — the library has no pre-existing specs under openspec/specs/. -->

## Impact

- **Code**: `src/main/java/info/unterrainer/commons/mqttclient/MqttClient.java` (rewired to `MqttAsyncClient`, listener wrapping), `MqttClientConfiguration.java` (new tunables), new internal `DispatchExecutor` (or equivalent) class, `SimpleMqttCallback.java` (unchanged in behavior, kept compatible). `MqttSubscriptionManager` is unaffected — it still calls `client.subscribe/unsubscribe`.
- **Public API**: source-compatible. New optional constructor / configuration fields are additive. Behavior change: listener invocation thread is no longer the Paho I/O thread; consumers must not assume serial ordering across topics (they could not safely assume it before either, but it is now explicit).
- **Dependencies**: no new artifacts. `org.eclipse.paho.client.mqttv3` 1.2.5 already exposes `MqttAsyncClient`.
- **Tests**: extend `MqttClientTests` with a slow-listener / parallel-topic test using an embedded broker or the existing test harness.
- **Downstream**: consumers that previously suffered head-of-line blocking on slow setters (e.g. `MqttApplianceSubscriptionManager` wrapping setters in `mutateState(..., 3000)`) become resilient without code changes on their side.
- **Risk**: message-loss surface area shifts from "wedge the broker connection" to "drop on saturation"; the default policy and its WARN log must be loud enough that operators notice.
