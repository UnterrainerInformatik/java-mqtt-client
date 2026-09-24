package info.unterrainer.commons.mqttclient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import info.unterrainer.commons.serialization.jsonmapper.JsonMapper;

public class MqttClientTests {

	/**
	 * Nothing listens on this port, so the connection is refused immediately
	 * rather than running into the connect timeout.
	 */
	private static final String NO_BROKER_THERE = "tcp://127.0.0.1:1";

	@Test
	public void reportsNotConnectedWhenTheBrokerIsNotThere() {
		MqttClient client = new MqttClient("test-client", NO_BROKER_THERE, JsonMapper.create());
		try {
			assertThat(client.isConnected()).isFalse();
		} finally {
			client.disconnect();
		}
	}

	@Test
	public void staysNotConnectedAfterAnotherFailedAttempt() {
		MqttClient client = new MqttClient("test-client", NO_BROKER_THERE, JsonMapper.create());
		try {
			client.connect();
			assertThat(client.isConnected()).isFalse();
		} finally {
			client.disconnect();
		}
	}
}
