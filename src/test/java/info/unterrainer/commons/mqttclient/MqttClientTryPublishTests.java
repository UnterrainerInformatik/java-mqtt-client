package info.unterrainer.commons.mqttclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import org.eclipse.paho.client.mqttv3.MqttException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class MqttClientTryPublishTests {

	// Nothing listens on port 1, so the initial connect is refused and the client
	// stays disconnected.
	private static final String UNREACHABLE_BROKER = "tcp://127.0.0.1:1";

	private MqttClient client;

	@BeforeEach
	public void setUp() {
		client = new MqttClient("try-publish-test", UNREACHABLE_BROKER, null);
	}

	@AfterEach
	public void tearDown() {
		try {
			client.disconnect();
		} catch (Exception e) {
			// best effort: the client never connected
		}
	}

	@Test
	public void disconnectedClientRefusesPublishWithCause() {
		assertThatThrownBy(() -> client.tryPublish("test/rpc", "{}", MqttQos.AT_LEAST_ONCE))
				.isInstanceOf(MqttPublishException.class)
				.hasCauseInstanceOf(MqttException.class)
				.hasMessageContaining("test/rpc");
	}

	@Test
	public void refusalIsReportedWithoutConnectAttempt() {
		long start = System.nanoTime();
		MqttPublishException e = catchThrowableOfType(
				() -> client.tryPublish("test/rpc", "{}", MqttQos.AT_LEAST_ONCE), MqttPublishException.class);
		long elapsedMs = (System.nanoTime() - start) / 1_000_000;

		assertThat(e).isNotNull();
		assertThat(e.getCause().getReasonCode()).isEqualTo(MqttException.REASON_CODE_CLIENT_NOT_CONNECTED);
		assertThat(elapsedMs).isLessThan(500);
	}
}
