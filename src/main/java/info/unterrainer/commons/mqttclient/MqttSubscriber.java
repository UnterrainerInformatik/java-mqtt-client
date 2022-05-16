package info.unterrainer.commons.mqttclient;

public interface MqttSubscriber<S> {

	void subscribeMqtt(MqttSubscriptionCollector<S> c);
}
