package info.unterrainer.commons.mqttclient;

import org.eclipse.paho.client.mqttv3.MqttException;

/**
 * Thrown by {@link MqttClient#tryPublish(String, String, MqttQos)} when the
 * client refused a message before it could be handed to the broker (for example
 * because it is not connected, or its in-flight window is full). The message
 * has definitely not been sent.
 */
public class MqttPublishException extends Exception {

	private static final long serialVersionUID = 1L;

	public MqttPublishException(final String topic, final MqttException cause) {
		super(String.format("Publishing to topic [%s] was refused (reason code %d): %s", topic,
				cause.getReasonCode(), cause.getMessage()), cause);
	}

	@Override
	public synchronized MqttException getCause() {
		return (MqttException) super.getCause();
	}
}
