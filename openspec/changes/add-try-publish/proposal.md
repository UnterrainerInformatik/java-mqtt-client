## Why

`MqttClient.send(...)` gives a caller no way to learn that a publish failed, and no bound on how long it blocks. It first calls `connect()` (synchronized, up to 10 s while the broker is unreachable), then waits for the QoS 1 PUBACK with `waitForCompletion()` without a timeout, and only logs a `MqttException`. overmind is about to send device commands as JSON-RPC over MQTT (overmind change `dimmer-g4-mqtt-rpc-commands`, design D7). It must fall back to HTTP when a command definitely did not reach the broker, and it must not park a command worker for longer than its reply timeout.

## What Changes

- New `MqttClient.tryPublish(topic, message, qos)`: hands the message to Paho without attempting to connect and without waiting for the PUBACK. It throws a new checked `MqttPublishException` (wrapping Paho's `MqttException`) when Paho refuses the message synchronously, for example because the client is not connected or the in-flight window is full.
- New exception type `MqttPublishException`.
- `send(...)` and every other existing method keep their current behaviour. No existing caller changes.
- Released as 1.0.8.

## Capabilities

### New Capabilities
- `mqtt-publish`: fail-fast, non-blocking publishing that reports a message the client could not hand to the broker.

### Modified Capabilities
_None._ `send(...)` is unchanged, so `mqtt-dispatch` ("Public API source compatibility") still holds.

## Impact

- Code: `MqttClient` (one new method) and a new `MqttPublishException`.
- API: additive only, so this is a patch release (1.0.8).
- Consumers: overmind-server bumps to 1.0.8 for `dimmer-g4-mqtt-rpc-commands`.
