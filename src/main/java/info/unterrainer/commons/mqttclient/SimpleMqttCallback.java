package info.unterrainer.commons.mqttclient;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SimpleMqttCallback implements MqttCallback {

	@Override
	public void connectionLost(final Throwable throwable) {
		log.debug("Connection to MQTT broker lost!");
	}

	@Override
	public void messageArrived(final String s, final MqttMessage mqttMessage) throws Exception {
		log.debug("Message received:\n\t" + new String(mqttMessage.getPayload()));
	}

	@Override
	public void deliveryComplete(final IMqttDeliveryToken iMqttDeliveryToken) {
		log.debug("Message delivery completed [{}]", iMqttDeliveryToken);
	}
}
