## Context

`info.unterrainer.commons.mqttclient.MqttClient` is a thin wrapper around Eclipse Paho `org.eclipse.paho.client.mqttv3.MqttClient` (sync flavor, v1.2.5). Today the wrapper:

- Constructs a sync `MqttClient`, sets a `SimpleMqttCallback`, and calls `connect()` from the constructor (`MqttClient.java:50-57`).
- Forwards `subscribe(topicFilter, listener)` straight to Paho (`MqttClient.java:119-126`). Paho dispatches every subscription's `messageArrived` on a single internal thread named `MQTT Call: <clientId>`.
- Wraps that in a typed `subscribe(topic, type, setter)` that parses the payload and calls `setter.accept(...)` on the same Paho thread (`MqttClient.java:138-182`).
- Sends with `client.publish` synchronously from the calling thread (`MqttClient.java:107`).
- `connect()` is `synchronized` and uses `cleanSession=false`, automatic reconnect, and a 10 s connect timeout.

Consumers freely register listeners that block — locks, HTTP/UDP polling, JSON-mapping into shared state. The most recent reproduction came from a downstream codebase wrapping the setter in a 3 s `mutateState` per-appliance lock; cascaded contention silenced MQTT for ~60 s before draining as a burst. The Paho dispatcher thread is the bottleneck, not the broker.

We want a structural fix in this library so any consumer is shielded from head-of-line blocking.

## Goals / Non-Goals

**Goals:**
- A single slow listener cannot delay messages destined for other listeners on the same `MqttClient`.
- Public API of `info.unterrainer.commons.mqttclient.MqttClient` stays source-compatible. Existing constructors, `subscribe(...)` signatures, `send`, `connect`, `disconnect` keep their semantics from the caller's point of view.
- Saturation behavior is bounded and observable: when the dispatch executor cannot keep up, we drop with a WARN log (default) instead of growing memory unbounded or pushing back into the Paho I/O thread.
- Lifecycle is tidy: the executor is created with the client and shut down on `disconnect()`; in-flight handlers get a bounded grace period to finish.
- Connection semantics from the caller's view stay the same: `connect()` returns only once the connection is established (within the existing 10 s timeout) or has failed.

**Non-Goals:**
- Not changing the Paho version or pulling in MQTT v5.
- Not changing `MqttSubscriptionManager`, `MqttSubscription`, `MqttQos`, the JSON parsing logic, or `SimpleMqttCallback`'s behavior (it stays a debug-logger).
- Not introducing per-listener priorities, retries, or per-topic ordering guarantees beyond what Paho already provides for a single listener.
- Not adding metrics/JMX in this change. We log saturation; richer telemetry can come later.
- Not solving downstream lock-contention bugs (e.g. the `mutateState` cascade) — that is the consumer's problem; this change just stops it from infecting the dispatcher.

## Decisions

### 1. Switch the underlying client to `MqttAsyncClient`
**Decision**: replace the sync `org.eclipse.paho.client.mqttv3.MqttClient` field with `MqttAsyncClient`.

**Why**: the sync client's "dispatch on one internal thread" model is the root cause. `MqttAsyncClient` returns `IMqttToken`s and lets us decide where work runs. We can preserve the sync feel of `connect()` and `send()` by `waiting` on the token where the public method previously blocked, but listener invocation is no longer chained to the I/O thread.

**Alternatives considered**:
- *Keep sync `MqttClient`, just wrap each listener in an executor submit.* This works for `messageArrived` but the wider dispatcher pipeline (delivery callbacks, connection-lost, `subscribe` ack handling) still runs on the single Paho thread. We get most of the benefit but lock ourselves out of future improvements (publish flow control, async connect retries). Deemed not worth the half-measure.
- *Use a different MQTT library (HiveMQ client, Vert.x mqtt).* Too large a blast radius for a small library; would break consumers.

### 2. Wrap every user listener in a dispatch executor
**Decision**: introduce an internal `DispatchExecutor` (a `ThreadPoolExecutor` with a bounded `ArrayBlockingQueue`) owned by `MqttClient`. Every `IMqttMessageListener` registered via the public `subscribe(...)` overloads is wrapped:

```
(topic, msg) -> dispatchExecutor.execute(() -> userListener.messageArrived(topic, msg))
```

The wrapping happens inside `MqttClient.subscribe(String, IMqttMessageListener)` so both the raw and the typed `subscribe(...)` overload benefit (the typed one funnels through it).

**Why**: this is the single chokepoint for "user code runs here". Wrapping at this seam guarantees no listener path bypasses the executor.

**Alternatives considered**:
- *Per-topic executor.* Stronger isolation but unbounded thread growth and trickier shutdown. Defer.
- *Use Paho's built-in `setExecutorServiceTimeout` / executor-service injection.* Paho's `MqttAsyncClient` supports an injected `ScheduledExecutorService` (used internally for ping/connect work). It does *not* dispatch user `messageArrived` on it — Paho still serializes per-message-listener dispatch on its callback thread. So injecting one alone does not solve our problem; we still need the wrapping. We will inject our scheduler to consolidate threads, but the listener wrapping stays.

### 3. Bounded queue + drop-oldest default
**Decision**: the dispatch executor has a fixed thread count (default 4) and a bounded queue (default 1024). When full, the saturation policy is **drop-oldest** with a WARN log throttled per topic.

**Why**: under sustained back-pressure, dropping is the only outcome that does not propagate the stall back into Paho. Drop-oldest favors freshness (recent state beats stale state, which matches typical IoT/state-publishing usage). A WARN log with a per-topic throttle (e.g. "at most one warning per topic per 5 s") makes loss visible without spamming.

**Alternatives considered**:
- *Drop-newest.* Preserves first-seen ordering, but for state-publishing consumers (the dominant user here) the *latest* value is what matters. Rejected as default; available as opt-in.
- *Block / `CallerRunsPolicy`.* Pushes the wedge back to the Paho thread — exactly what we are trying to escape. Rejected as default; available as opt-in for callers who can guarantee fast handlers.
- *Unbounded queue.* OOM hazard. Rejected.

The policy is selectable via `MqttClientConfiguration` (`DROP_OLDEST` | `DROP_NEWEST` | `BLOCK`).

### 4. Configuration surface
**Decision**: add fields to `MqttClientConfiguration` (with env-var overrides matching the existing `OVERMIND_MQTT_*` style) and one new `MqttClient` constructor overload that takes a built `MqttClientConfiguration` directly. Defaults:

| Setting                | Default        | Env override                          |
|------------------------|----------------|---------------------------------------|
| dispatchThreads        | 4              | `OVERMIND_MQTT_DISPATCH_THREADS`      |
| dispatchQueueCapacity  | 1024           | `OVERMIND_MQTT_DISPATCH_QUEUE`        |
| saturationPolicy       | `DROP_OLDEST`  | `OVERMIND_MQTT_SATURATION_POLICY`     |
| dispatchShutdownMillis | 5000           | `OVERMIND_MQTT_DISPATCH_SHUTDOWN_MS`  |

The existing constructors keep their signatures and use the defaults — no source-level break for current callers.

**Why**: the existing config already reads env vars; following that idiom means consumers get tunability without code changes. New constructor avoids constructor explosion.

### 5. Lifecycle and shutdown
**Decision**: the dispatch executor is created in the `MqttClient` constructor and shut down inside `disconnect()`:

1. `client.disconnect()` (Paho async) is invoked first; we wait on the token (10 s) so no new messages arrive.
2. `dispatchExecutor.shutdown()`, then `awaitTermination(dispatchShutdownMillis, MILLISECONDS)`.
3. If termination times out, `shutdownNow()` and log the count of dropped tasks.

**Why**: stop the producer (Paho) before draining the consumer (executor); avoids a flood of late tasks during shutdown. The bounded grace period keeps `disconnect()` from hanging forever on a stuck listener.

### 6. `connect()` and `send()` keep their blocking feel
**Decision**: where Paho async returns tokens, the wrapper waits on the token with the same timeout the sync version implied:
- `connect()`: `connectToken.waitForCompletion(10_000)`. Same 10 s budget as today's `setConnectionTimeout(10)`.
- `send(...)`: `publishToken.waitForCompletion()` (no explicit timeout, matching today's blocking publish). Future change can introduce a timeout, out of scope here.

**Why**: callers today rely on `connect()` returning when the connection is up and `send()` returning when the message is handed off. Preserving this avoids surprise behavior changes. The async client is an internal substitution.

### 7. `SimpleMqttCallback` stays put
**Decision**: keep `SimpleMqttCallback` wired as `client.setCallback(...)` and unchanged. It only logs at debug level — no offload needed.

**Why**: smallest possible diff at the callback seam. The `messageArrived` *on the callback* fires only for messages with no per-topic listener; with Paho 1.2.5 typical usage it is dormant. If a consumer ever adds heavy logic there, the same wrapping pattern can be applied later.

## Risks / Trade-offs

- **Risk**: silent message loss under sustained saturation (drop-oldest). → **Mitigation**: throttled WARN log per topic on drop; expose counters via a simple `getDroppedCount()` accessor for tests; document the policy in the spec; allow opting into `BLOCK` for callers who must not drop.
- **Risk**: ordering surprises — listeners on the same topic that previously ran strictly in arrival order are now scheduled through a thread pool with N>1 threads. → **Mitigation**: with default 4 threads, two messages on the *same topic* can execute out of order. Document this clearly; provide a single-thread option (`dispatchThreads=1`) for callers needing per-topic ordering. Most current consumers (state setters) are idempotent w.r.t. order beyond "latest wins".
- **Risk**: shutdown can drop in-flight tasks if `dispatchShutdownMillis` is too small. → **Mitigation**: default 5 s is generous for typical handlers; tunable; shutdown logs the dropped count so it is not silent.
- **Risk**: switching to `MqttAsyncClient` may surface latent `MqttException` paths that the sync API masked (e.g. `subscribe` returning a token before the SUBACK). → **Mitigation**: in `subscribe(...)` we wait on the subscribe token (short timeout, e.g. 5 s) so the caller still sees a synchronous "subscribed-or-failed" outcome. Failures log and propagate the same way the sync path does today.
- **Risk**: behavioral change is invisible in unit tests that use a mock listener returning instantly. → **Mitigation**: add a regression test that registers a deliberately blocking listener on topic A and asserts that a message on topic B is delivered within a tight deadline.
- **Trade-off**: small extra latency per message (one queue hop, one thread handoff). For an IoT state-update workload this is negligible (microseconds vs. seconds of head-of-line risk eliminated).
- **Trade-off**: the `synchronized` on `connect()` becomes less meaningful with an async client; we keep it to preserve the existing "single concurrent connect attempt" guarantee.

## Migration Plan

This is a library-internal substitution; there is no data migration. Rollout:

1. Land the change as a minor version bump (`1.0.0` → `1.1.0`). API source-compatible; behavior change documented in `CHANGELOG`-equivalent (commit message + PR description).
2. Downstream consumers pick up by bumping the dependency. No code changes required for the default policy.
3. Rollback: revert the dependency bump. No state to migrate back.

## Open Questions

- Should saturation drops emit at WARN or ERROR? (Leaning WARN; ERROR feels excessive for an expected back-pressure outcome.)
- Should we expose a counter accessor (`droppedCount`, `queueDepth`) on `MqttClient` now, or wait for a follow-up? (Leaning: expose minimal counters now, since they cost little and unlock test assertions.)
- Default `dispatchThreads=4` — chosen by analogy to typical small IoT consumers; verify against the highest-fanout consumer before locking it in.
