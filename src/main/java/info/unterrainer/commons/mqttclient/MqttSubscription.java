package info.unterrainer.commons.mqttclient;

import java.util.function.BiConsumer;

import lombok.Builder;
import lombok.Data;

@Builder
@Data
public class MqttSubscription {

	private Long applianceId;
	private String topic;
	private Class<?> type;
	private BiConsumer<String, ?> setter;
}
