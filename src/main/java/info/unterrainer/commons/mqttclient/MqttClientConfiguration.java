package info.unterrainer.commons.mqttclient;

import java.util.Optional;

import lombok.Getter;
import lombok.experimental.Accessors;

@Accessors(fluent = true)
@Getter
public class MqttClientConfiguration {

	private MqttClientConfiguration() {
	}

	private String mqttServer;

	public static MqttClientConfiguration read() {
		return read(null);
	}

	public static MqttClientConfiguration read(final String prefix) {
		String p = "";
		if (prefix != null)
			p = prefix;
		MqttClientConfiguration config = new MqttClientConfiguration();

		config.mqttServer = Optional.ofNullable(System.getenv(p + "OVERMIND_MQTT_SERVER"))
				.orElse("tcp://10.10.196.4:1883");

		return config;
	}
}
