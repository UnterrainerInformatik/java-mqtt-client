package info.unterrainer.commons.mqttclient;

import java.util.function.BiConsumer;

import lombok.Data;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Data
public class MqttSubscription {

	protected String topic;
	protected Class<?> type;
	protected BiConsumer<String, ?> setter;
}
