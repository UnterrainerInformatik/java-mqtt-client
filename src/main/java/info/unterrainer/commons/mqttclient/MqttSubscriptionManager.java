package info.unterrainer.commons.mqttclient;

import java.util.HashMap;
import java.util.Map;

import info.unterrainer.commons.jreutils.SetIntersection;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MqttSubscriptionManager<T extends MqttSubscription> {

	private final MqttClient client;
	private boolean takeSecondMap;
	private Map<String, T> map1 = new HashMap<>();
	private Map<String, T> map2 = new HashMap<>();

	public void updateDifferentialSubscriptionsOnClient() {
		client.connect();
		SetIntersection intersection = SetIntersection.of(getOldMap().keySet(), getCurrentMap().keySet());
		for (String topic : intersection.getDelete())
			client.unsubscribe(topic);
		Map<String, T> map = getCurrentMap();
		for (String topic : intersection.getCreate()) {
			T subscription = map.get(topic);
			client.subscribe(subscription.getTopic(), subscription.getType(), subscription.getSetter());
		}
	}

	public void unsubscribeAllSubscriptionsOnClient() {
		client.connect();
		Map<String, T> map = getCurrentMap();
		for (T subscription : map.values())
			client.unsubscribe(subscription.getTopic());
	}

	public void switchBuffer() {
		takeSecondMap = !takeSecondMap;
		getCurrentMap().clear();
	}

	public Map<String, T> getCurrentMap() {
		Map<String, T> map = map1;
		if (takeSecondMap)
			map = map2;
		return map;
	}

	public Map<String, T> getOldMap() {
		Map<String, T> map = map1;
		if (!takeSecondMap)
			map = map2;
		return map;
	}
}
