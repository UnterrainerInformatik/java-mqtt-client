## Purpose

Lets a caller publish a message without blocking on a reconnect or on the broker's acknowledgement, and learn at once when the client could not hand the message over, so the caller can choose another path for it.

## ADDED Requirements

### Requirement: Fail-fast publish reports a refused message

The client SHALL offer a publish operation that hands a message to the MQTT connection and returns without waiting for the broker's acknowledgement. When the client cannot hand the message over (for example because it is not connected, or its in-flight window is full), the operation SHALL fail with a checked exception that carries the underlying cause. It SHALL NOT log the failure as an error itself, because handling the failure is the caller's decision.

#### Scenario: Connected client
- **WHEN** a message is published while the client is connected
- **THEN** the operation returns normally and the message is delivered with the requested QoS

#### Scenario: Disconnected client
- **WHEN** a message is published while the client is not connected to the broker
- **THEN** the operation fails with the checked publish exception, whose cause names the refusal

### Requirement: Fail-fast publish never blocks on the connection

The fail-fast publish operation SHALL NOT attempt to (re)connect and SHALL NOT wait for the broker's acknowledgement. Its duration SHALL therefore not depend on the broker's reachability or responsiveness. Reconnecting remains the job of the client's automatic reconnect.

#### Scenario: Broker unreachable
- **WHEN** a message is published while the broker is unreachable
- **THEN** the operation fails within a short time (well below one second) instead of waiting for a connect attempt

### Requirement: Existing send behaviour is unchanged

Adding the fail-fast publish operation SHALL NOT change the behaviour of the existing send operations, which keep connecting on demand, waiting for completion and logging failures.

#### Scenario: Existing caller
- **WHEN** an existing caller uses a send operation
- **THEN** it behaves exactly as in version 1.0.7
