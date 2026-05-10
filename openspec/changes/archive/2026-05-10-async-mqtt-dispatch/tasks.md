## 1. Configuration surface

- [x] 1.1 Add `dispatchThreads` (int, default 4) to `MqttClientConfiguration` with env override `OVERMIND_MQTT_DISPATCH_THREADS`
- [x] 1.2 Add `dispatchQueueCapacity` (int, default 1024) with env override `OVERMIND_MQTT_DISPATCH_QUEUE`
- [x] 1.3 Add `SaturationPolicy` enum (`DROP_OLDEST`, `DROP_NEWEST`, `BLOCK`) and `saturationPolicy` field (default `DROP_OLDEST`) with env override `OVERMIND_MQTT_SATURATION_POLICY`
- [x] 1.4 Add `dispatchShutdownMillis` (long, default 5000) with env override `OVERMIND_MQTT_DISPATCH_SHUTDOWN_MS`
- [x] 1.5 Update `MqttClientConfiguration.read(prefix)` to read the new env vars and apply defaults; keep prefix handling consistent with existing `mqttServer` field

## 2. Dispatch executor

- [x] 2.1 Create package-private `DispatchExecutor` class wrapping a `ThreadPoolExecutor` with a fixed pool of `dispatchThreads` and an `ArrayBlockingQueue` of `dispatchQueueCapacity`
- [x] 2.2 Implement `submit(String topic, Runnable task)` that applies the configured saturation policy:
  - `DROP_OLDEST`: poll head, log throttled WARN, then offer new task
  - `DROP_NEWEST`: discard `task`, log throttled WARN
  - `BLOCK`: `put(task)` (blocking)
- [x] 2.3 Implement per-topic WARN throttling (≥1 WARN per topic per 5 s) backed by a `ConcurrentHashMap<String, Long>` of last-warn timestamps
- [x] 2.4 Expose minimal observability: `getDroppedCount()` and `getQueueDepth()` for tests
- [x] 2.5 Implement `shutdown(long timeoutMillis)` returning the count of abandoned tasks; logs WARN if non-zero
- [x] 2.6 Name dispatch threads `mqtt-dispatch-<clientId>-<n>` for easier diagnosis

## 3. Switch MqttClient internals to MqttAsyncClient

- [x] 3.1 Replace the `org.eclipse.paho.client.mqttv3.MqttClient` field in `MqttClient.java` with `MqttAsyncClient`
- [x] 3.2 Update the constructor chain: instantiate `MqttAsyncClient`, set `SimpleMqttCallback` (unchanged), construct the `DispatchExecutor`, then call `connect()`
- [x] 3.3 Update `connect()`: build `MqttConnectOptions` as today; call `client.connect(options)` and `waitForCompletion(10_000)` on the returned token; preserve the `synchronized` modifier and the early-return-when-connected check
- [x] 3.4 Update `send(...)`: call async `publish(...)` and `waitForCompletion()` on the returned token; preserve existing exception handling and log messages
- [x] 3.5 Update `unsubscribe(...)`: call async `unsubscribe(...)` and `waitForCompletion(5_000)`
- [x] 3.6 Update `disconnect()`: call async `disconnect(...)` and `waitForCompletion(10_000)`, then call `dispatchExecutor.shutdown(dispatchShutdownMillis)`

## 4. Wrap subscription listeners through the dispatch executor

- [x] 4.1 In `subscribe(String topicFilter, IMqttMessageListener listener)`, wrap the user listener as `(t, m) -> dispatchExecutor.submit(t, () -> listener.messageArrived(t, m))` before calling `client.subscribe(...)`; wait on the subscribe token for up to 5 s; log on failure as today
- [x] 4.2 Verify the typed `subscribe(topic, type, setter)` overload funnels through (4.1) so the typed setter also runs on the executor — no separate change needed if it already calls the raw `subscribe(...)`
- [x] 4.3 Catch `Throwable` inside the wrapped runnable so a failing handler cannot poison the dispatch thread; log at ERROR with topic context

## 5. New constructor overload

- [x] 5.1 Add `MqttClient(String clientId, MqttClientConfiguration config, JsonMapper jsonMapper)` that pulls server, QoS, retain, and dispatch settings from the config
- [x] 5.2 Keep all existing constructors; they delegate to the new path with default dispatch settings (no behavior change for current callers)

## 6. Tests

- [x] 6.1 Extend `MqttClientTests` with a regression test: register a listener on topic `A` that sleeps 5 s; publish to `A` and to `B`; assert `B`'s listener fires within 1 s of publish
- [x] 6.2 Add a saturation test: configure `dispatchQueueCapacity=4`, `dispatchThreads=1`, `saturationPolicy=DROP_OLDEST`; publish a burst of 20 messages with a slow listener; assert `getDroppedCount() > 0` and the WARN log fires (throttled to once per topic in the window)
- [x] 6.3 Add a `BLOCK` policy test: configure `BLOCK`, fill the queue, and assert that the dispatcher applies back-pressure (incoming `submit` blocks until capacity frees up)
- [x] 6.4 Add a disconnect-drain test: queue several handlers, call `disconnect()`, assert all complete within `dispatchShutdownMillis`
- [x] 6.5 Add a disconnect-timeout test: queue a handler that blocks indefinitely, call `disconnect()` with a small `dispatchShutdownMillis`, assert it returns within budget and logs the abandoned count
- [x] 6.6 Add a `MqttClientConfiguration` test that covers defaults and env-var overrides for the four new fields

## 7. Documentation

- [x] 7.1 Update `README.md` with a short section on dispatch behavior, the saturation policy options, and the env vars
- [x] 7.2 Add a JavaDoc note on `subscribe(...)` overloads stating that listeners run on the dispatch executor and are not strictly per-topic ordered when `dispatchThreads > 1`
- [x] 7.3 Bump `pom.xml` version `1.0.0` → `1.1.0`

## 8. Verification

- [x] 8.1 Run `mvn test` and confirm all existing tests still pass
- [x] 8.2 Run the new regression tests under `mvn test` and confirm they pass
- [x] 8.3 Run `openspec verify async-mqtt-dispatch` (or the project's spec-driven verifier) and confirm all requirements are covered
