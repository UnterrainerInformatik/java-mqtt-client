# mqtt-dispatch Specification

## Purpose
TBD - created by archiving change async-mqtt-dispatch. Update Purpose after archive.
## Requirements
### Requirement: Listener invocation isolated from Paho I/O thread
The `MqttClient` SHALL invoke every user-supplied subscription listener (registered through any `subscribe(...)` overload) on a dedicated dispatch executor owned by the client, NOT on the Paho callback thread.

#### Scenario: Slow listener does not block other listeners
- **WHEN** a listener registered for topic `A` blocks for 5 seconds
- **AND** a message is published to topic `B` 100 ms after the message to `A`
- **THEN** the listener for topic `B` SHALL be invoked within 1 second of publish, regardless of the listener for `A` still blocking

#### Scenario: Slow listener does not block client housekeeping
- **WHEN** a listener registered for any topic blocks indefinitely
- **THEN** subsequent calls to `subscribe(...)`, `unsubscribe(...)`, `send(...)`, and `disconnect()` from another thread SHALL NOT be blocked by that listener

### Requirement: Bounded dispatch queue with selectable saturation policy
The dispatch executor SHALL use a bounded task queue. When the queue is full, the configured saturation policy SHALL be applied. The supported policies are `DROP_OLDEST` (default), `DROP_NEWEST`, and `BLOCK`.

#### Scenario: Drop-oldest discards the oldest queued task
- **GIVEN** the saturation policy is `DROP_OLDEST` and the queue is at capacity
- **WHEN** a new message arrives that would be enqueued
- **THEN** the oldest queued task SHALL be discarded and the new task SHALL be enqueued

#### Scenario: Drop-newest discards the incoming task
- **GIVEN** the saturation policy is `DROP_NEWEST` and the queue is at capacity
- **WHEN** a new message arrives that would be enqueued
- **THEN** the new task SHALL be discarded and the queue SHALL remain unchanged

#### Scenario: Block applies back-pressure to the dispatcher
- **GIVEN** the saturation policy is `BLOCK` and the queue is at capacity
- **WHEN** a new message arrives that would be enqueued
- **THEN** the dispatching call SHALL block until queue capacity becomes available

### Requirement: Saturation events SHALL be observable
The client SHALL emit a WARN-level log entry when a dispatch task is dropped because of the saturation policy. To avoid log spam, the warning SHALL be throttled to at most one entry per topic per 5 seconds.

#### Scenario: First drop on a topic logs a warning
- **WHEN** a task for topic `T` is dropped due to saturation
- **AND** no warning for topic `T` has been emitted within the prior 5 seconds
- **THEN** a single WARN log entry SHALL be emitted naming topic `T` and the policy that fired

#### Scenario: Repeated drops within the throttle window are suppressed
- **GIVEN** a WARN entry for topic `T` was emitted 1 second ago
- **WHEN** another task for topic `T` is dropped within the next 4 seconds
- **THEN** no additional WARN entry for topic `T` SHALL be emitted

### Requirement: Configurable dispatch executor
`MqttClientConfiguration` SHALL expose `dispatchThreads`, `dispatchQueueCapacity`, `saturationPolicy`, and `dispatchShutdownMillis`. Each setting SHALL be overridable through an environment variable using the existing `OVERMIND_MQTT_*` naming convention.

#### Scenario: Defaults are applied when no overrides are set
- **WHEN** `MqttClientConfiguration.read()` is called with no relevant environment variables set
- **THEN** the resulting configuration SHALL report `dispatchThreads=4`, `dispatchQueueCapacity=1024`, `saturationPolicy=DROP_OLDEST`, and `dispatchShutdownMillis=5000`

#### Scenario: Environment variables override defaults
- **GIVEN** `OVERMIND_MQTT_DISPATCH_THREADS=8` and `OVERMIND_MQTT_SATURATION_POLICY=BLOCK` are set in the environment
- **WHEN** `MqttClientConfiguration.read()` is called
- **THEN** the resulting configuration SHALL report `dispatchThreads=8` and `saturationPolicy=BLOCK`

#### Scenario: Existing constructors continue to apply defaults
- **WHEN** an existing `MqttClient` constructor (those present prior to this change) is invoked
- **THEN** the dispatch executor SHALL be created using the default settings above without requiring any caller-side change

### Requirement: Public API source compatibility
This change SHALL preserve the source-level compatibility of `MqttClient`'s existing public methods: `connect()`, `disconnect()`, `send(...)` overloads, `subscribe(...)` overloads, `unsubscribe(...)`, and `topicsMatch(...)`. The observable semantics (returning only after connect succeeds, blocking until publish is handed off, throwing nothing the previous version did not throw) SHALL be preserved.

#### Scenario: Existing callers compile and behave identically for happy path
- **GIVEN** a caller using only constructors and methods present in `MqttClient` prior to this change
- **WHEN** the caller is recompiled against the new version with no source changes
- **THEN** the code SHALL compile
- **AND** in the absence of dispatcher saturation, observable behavior of `connect`, `send`, `subscribe`, `unsubscribe`, and `disconnect` SHALL be unchanged

### Requirement: Underlying client uses MqttAsyncClient
The internal Paho client SHALL be `org.eclipse.paho.client.mqttv3.MqttAsyncClient`. Public methods on the wrapper that previously appeared synchronous (`connect`, `send`, `subscribe`, `unsubscribe`, `disconnect`) SHALL wait on the corresponding Paho token to preserve their blocking-call semantics.

#### Scenario: connect waits for CONNACK within the existing 10 s budget
- **WHEN** `connect()` is called against a reachable broker
- **THEN** the call SHALL return only after the connect token completes
- **AND** the maximum wait SHALL be 10 seconds, after which an error SHALL be logged exactly as the prior implementation logged connect failures

#### Scenario: subscribe waits for SUBACK before returning
- **WHEN** `subscribe(topicFilter, listener)` is called against a connected broker
- **THEN** the call SHALL return only after the broker acknowledges the subscription, or after a bounded timeout (5 s) elapses with the failure logged

### Requirement: Clean lifecycle on disconnect
`disconnect()` SHALL stop accepting new messages from the broker before draining the dispatch executor, and SHALL bound the drain by `dispatchShutdownMillis`. Tasks that are still in the queue when the bound elapses SHALL be discarded and their count logged at WARN.

#### Scenario: Disconnect drains in-flight handlers within the configured budget
- **GIVEN** the dispatch executor has 3 in-flight handlers, each completing within 200 ms
- **WHEN** `disconnect()` is called
- **THEN** the call SHALL return after all 3 handlers have completed
- **AND** the total wait SHALL NOT exceed `dispatchShutdownMillis`

#### Scenario: Disconnect bounds the wait when a handler hangs
- **GIVEN** the dispatch executor has 1 in-flight handler that does not return
- **WHEN** `disconnect()` is called with `dispatchShutdownMillis=5000`
- **THEN** the call SHALL return within ~5 seconds
- **AND** a WARN log entry SHALL report the count of tasks abandoned

