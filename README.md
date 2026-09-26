# java-mqtt-client

This is a simple client for MQTT that allows you to connect to an existing MQTT server and to subscribe (and update continuously, should the list of topics to subscribe to change) topics there.

## Asynchronous dispatch

Subscription listeners run on a bounded dispatch executor owned by the client, not on the Paho I/O thread. A slow listener on one topic cannot block delivery on another. When `dispatchThreads > 1`, listeners may observe messages on the same topic out of strict arrival order; set `dispatchThreads = 1` if per-topic ordering is required.

When the dispatch queue is full, the configured saturation policy decides what happens:

- `DROP_OLDEST` (default) — drops the oldest queued task and enqueues the new one
- `DROP_NEWEST` — drops the incoming task
- `BLOCK` — applies back-pressure, blocking the dispatching call until capacity frees up

Drop events emit a WARN log per topic, throttled to one entry per topic per 5 seconds. `MqttClient#getDroppedDispatchCount()` and `MqttClient#getDispatchQueueDepth()` expose minimal counters.

`disconnect()` first stops the broker connection, then drains in-flight handlers within `dispatchShutdownMillis`; tasks still queued when the budget elapses are abandoned and logged at WARN.

## Fail-fast publishing

`send(...)` connects on demand, waits for the broker's acknowledgement and only logs failures. When a caller needs to know that a message could not be sent, and must not block on the broker, use `tryPublish(topic, message, qos)`. It does not reconnect and does not wait for the acknowledgement. It throws the checked `MqttPublishException` when the client refuses the message (for example while disconnected), in which case the message has definitely not been sent. Loss after the hand-over is not reported, so confirm delivery through a reply if you need it.

```java
try {
	client.tryPublish("device/rpc", payload, MqttQos.AT_LEAST_ONCE);
} catch (MqttPublishException e) {
	// not sent: take another path
}
```

## Configuration

| Setting                | Default       | Env override                          |
|------------------------|---------------|---------------------------------------|
| mqttServer             | tcp://10.10.196.4:1883 | `OVERMIND_MQTT_SERVER`       |
| dispatchThreads        | 4             | `OVERMIND_MQTT_DISPATCH_THREADS`      |
| dispatchQueueCapacity  | 1024          | `OVERMIND_MQTT_DISPATCH_QUEUE`        |
| saturationPolicy       | `DROP_OLDEST` | `OVERMIND_MQTT_SATURATION_POLICY`     |
| dispatchShutdownMillis | 5000          | `OVERMIND_MQTT_DISPATCH_SHUTDOWN_MS`  |

`MqttClientConfiguration.read(prefix)` accepts an optional prefix that is prepended to every env-var name, so multiple clients can be configured side by side.

Use the configuration with the new constructor:

```java
MqttClientConfiguration config = MqttClientConfiguration.read();
MqttClient client = new MqttClient("my-client-id", config, jsonMapper);
```

The pre-existing constructors that take a server address directly continue to work and use the default dispatch settings.
