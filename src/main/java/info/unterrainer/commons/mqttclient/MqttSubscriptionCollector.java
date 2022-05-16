package info.unterrainer.commons.mqttclient;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MqttSubscriptionCollector<S> {

	@Getter
	private final Long applianceId;
	private final String topicPrefix;
	@Getter
	private String topic;
	@Getter
	private SettingConsumer<String, S> setter;
	@Getter
	private Class<?> type;

	@SuppressWarnings("unchecked")
	public <T> void subscribe(final String topic, final Class<T> type, final TriConsumer<String, T, S> setter) {
		this.setter = (to, t, s) -> {
			setter.accept(to, (T) t, s);
		};
		this.type = type;
		this.topic = topicPrefix + topic;
	}
}
