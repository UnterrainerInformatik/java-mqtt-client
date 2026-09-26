## 1. Implementation

- [x] 1.1 Add checked `MqttPublishException` (cause = Paho `MqttException`; message names topic and reason code) (design D2)
- [x] 1.2 Add `MqttClient.tryPublish(topic, message, qos)`: build the message like `send`, `retained=false`, `client.publish(...)` without `connect()` and without waiting on the token; wrap `MqttException` into `MqttPublishException` without logging; javadoc states that delivery after hand-over is not reported (design D1, D3)

## 2. Tests

- [x] 2.1 Test: client pointed at an unreachable broker (e.g. `tcp://127.0.0.1:1`) → `tryPublish` throws `MqttPublishException` with a `MqttException` cause, and returns in well under one second
- [x] 2.2 `mvn test` green

## 3. Release (needs Gerald's go-ahead)

- [x] 3.1 Update README (API section) with `tryPublish`
- [x] 3.2 Commit and push to `master` → pipeline bumps to 1.0.8 and publishes to Maven Central; confirm the artifact is available
