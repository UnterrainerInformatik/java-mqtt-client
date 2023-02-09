package info.unterrainer.commons.mqttclient;

import java.util.HashMap;
import java.util.Map;

import info.unterrainer.commons.jreutils.SetIntersection;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MqttSubscriptionManager<U, T extends MqttSubscription> {

	private final MqttClient client;
	private boolean takeSecondMap;
	private Map<U, T> map1 = new HashMap<>();
	private Map<U, T> map2 = new HashMap<>();

	public void updateDifferentialSubscriptionsOnClient() {
		client.connect();
		Map<U, T> oldMap = getOldMap();
		Map<U, T> newMap = getCurrentMap();
		SetIntersection<U> intersection = SetIntersection.of(oldMap.keySet(), newMap.keySet());
		// Deleted subscriptions.
		for (U id : intersection.getDelete())
			client.unsubscribe(oldMap.get(id).getTopic());
		// Changes in existing subscriptions.
		for (U id : intersection.getLeave()) {
			T oldSub = oldMap.get(id);
			T newSub = newMap.get(id);
			if (!oldSub.getTopic().equals(newSub.getTopic()) || oldSub.getType() != newSub.getType()) {
				client.unsubscribe(oldSub.getTopic());
				client.subscribe(newSub.getTopic(), newSub.getType(), newSub.getSetter());
			}
		}
		// New subscriptions.
		for (U id : intersection.getCreate()) {
			T subscription = newMap.get(id);
			client.subscribe(subscription.getTopic(), subscription.getType(), subscription.getSetter());
		}
	}

	public void unsubscribeAllSubscriptionsOnClient() {
		client.connect();
		Map<U, T> map = getCurrentMap();
		for (T subscription : map.values())
			client.unsubscribe(subscription.getTopic());
	}

	public void switchBuffer() {
		takeSecondMap = !takeSecondMap;
		getCurrentMap().clear();
	}

	public Map<U, T> getCurrentMap() {
		Map<U, T> map = map1;
		if (takeSecondMap)
			map = map2;
		return map;
	}

	public Map<U, T> getOldMap() {
		Map<U, T> map = map1;
		if (!takeSecondMap)
			map = map2;
		return map;
	}
}
