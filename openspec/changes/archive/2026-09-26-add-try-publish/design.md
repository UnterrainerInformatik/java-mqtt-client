## Context

- `MqttClient` wraps a Paho `MqttAsyncClient` with `setAutomaticReconnect(true)` and `cleanSession=false`. Offline buffering (`DisconnectedBufferOptions`) is not enabled, so Paho's `publish(...)` throws `MqttException` with `REASON_CODE_CLIENT_NOT_CONNECTED` right away while disconnected, and `REASON_CODE_MAX_INFLIGHT` when the in-flight window is full.
- `send(...)` calls `connect()` first and then `token.waitForCompletion()` without a timeout, and it swallows `MqttException` into an ERROR log. See proposal.md (Why) for why a caller needs more than that.

## Goals / Non-Goals

**Goals:**
- A publish whose failure the caller can see, and whose duration does not depend on the broker.

**Non-Goals:**
- Changing `send(...)`, `connect()` or the reconnect strategy.
- Exposing the delivery token or offering a publish that waits with a timeout. The first consumer (overmind RPC) learns about delivery from the device's reply, so it does not need either.
- Enabling Paho's offline buffering. A buffered message would be sent later, after the caller had already fallen back, and that would double-apply a command.

## Decisions

### D1: `tryPublish(String topic, String message, MqttQos qos) throws MqttPublishException`

The method builds the `MqttMessage` exactly like `send` does (bytes of the string, QoS mode, `retained=false`), calls `client.publish(topic, m)` and returns without waiting on the token. A thrown `MqttException` (including `MqttPersistenceException`) is wrapped into `MqttPublishException` and not logged.

*Alternative considered:* an overload `send(..., long timeoutMs)` that throws. Rejected because it would still call `connect()` (the 10 s block), and the name would suggest the same semantics as `send`.

*Alternative considered:* an unchecked exception. Rejected because a refused publish is an expected, recoverable situation that callers should be forced to handle.

### D2: `MqttPublishException` is a checked exception

It extends `Exception` and keeps the Paho `MqttException` as its cause. Its message names the topic and Paho's reason code, so callers can log it without unwrapping. It lives in `info.unterrainer.commons.mqttclient`.

### D3: No retain parameter

Command publishing never wants to retain, and a retained command would be replayed to the device on its next reconnect. An overload can be added later without breaking anyone.

## Risks / Trade-offs

- [A message handed to Paho can still be lost later, for example when the connection drops before the PUBACK] → By design the caller cannot see this. The overmind RPC caller covers it with its reply timeout, and the javadoc states it.
- [Paho's `publish` may also throw for a malformed topic] → That is still a refusal before the broker, so it is reported the same way and the caller's fallback is safe.

## Migration Plan

Additive patch release. Merge to `master`, which triggers the pipeline: SemVer bump to 1.0.8 and publish to Maven Central. Then bump the version in overmind-server. Rollback means consumers stay on 1.0.7.
