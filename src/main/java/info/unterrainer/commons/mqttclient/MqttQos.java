package info.unterrainer.commons.mqttclient;

import lombok.Getter;

public enum MqttQos {

	AT_MOST_ONCE(0),
	AT_LEAST_ONCE(1),
	EXACTLY_ONCE(2);

	@Getter
	private final int mode;

	MqttQos(final int mode) {
		this.mode = mode;
	}
}
