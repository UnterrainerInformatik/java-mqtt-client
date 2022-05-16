package info.unterrainer.commons.mqttclient;

import java.util.HashMap;
import java.util.Map;

import info.unterrainer.commons.jreutils.SetIntersection;
import info.unterrainer.server.overmindserver.baseobjects.BasicAppliance;
import info.unterrainer.server.overmindserver.loaders.ApplianceLoader;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MqttSubscriptionManager {

	private final SimpleMqttClient client;
	private final ApplianceLoader applianceLoader;
	private boolean takeSecondMap;
	private Map<String, MqttSubscription> map1 = new HashMap<>();
	private Map<String, MqttSubscription> map2 = new HashMap<>();

	@SuppressWarnings("unchecked")
	public <S> void subscribe(final MqttSubscriptionCollector<S> c) {
		getCurrentMap().put(c.getTopic(),
				MqttSubscription.builder()
						.applianceId(c.getApplianceId())
						.topic(c.getTopic())
						.type(c.getType())
						.setter((topic, v) -> {
							BasicAppliance<?, ?> appliance = applianceLoader.itemByIdMap.get(c.getApplianceId());
							appliance.mutateState(s -> {
								c.getSetter().accept(topic, v, (S) s);
							}, 3000);
						})
						.build());
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	public void update(final ApplianceLoader applianceLoader) {
		switchBuffer();
		for (BasicAppliance<?, ?> appliance : applianceLoader.getMqttSubscribersByIdMap().values()) {
			MqttSubscriber<?> sub = (MqttSubscriber<?>) appliance;
			MqttSubscriptionCollector c = new MqttSubscriptionCollector(appliance.getId(),
					"shellies/" + appliance.getConfiguration()
							.getAddress()
							.replace("http://", "")
							.replace("https://", "")
							.replace("tcp://", "")
							.replace(".", "-"));
			synchronized (this) {
				sub.subscribeMqtt(c);
				subscribe(c);
			}
		}
		updateDifferentialSubscriptionsOnClient();
	}

	public void updateDifferentialSubscriptionsOnClient() {
		client.connect();
		SetIntersection intersection = SetIntersection.of(getCurrentMap().keySet(), getOldMap().keySet());
		for (String topic : intersection.getDelete())
			client.unsubscribe(topic);
		Map<String, MqttSubscription> map = getCurrentMap();
		for (String topic : intersection.getCreate()) {
			MqttSubscription subscription = map.get(topic);
			client.subscribe(subscription.getTopic(), subscription.getType(), subscription.getSetter());
		}
	}

	public void unsubscribeAllSubscriptionsOnClient() {
		client.connect();
		Map<String, MqttSubscription> map = getCurrentMap();
		for (MqttSubscription subscription : map.values())
			client.unsubscribe(subscription.getTopic());
	}

	public void switchBuffer() {
		takeSecondMap = !takeSecondMap;
		getCurrentMap().clear();
	}

	public Map<String, MqttSubscription> getCurrentMap() {
		Map<String, MqttSubscription> map = map1;
		if (takeSecondMap)
			map = map2;
		return map;
	}

	public Map<String, MqttSubscription> getOldMap() {
		Map<String, MqttSubscription> map = map1;
		if (!takeSecondMap)
			map = map2;
		return map;
	}
}
