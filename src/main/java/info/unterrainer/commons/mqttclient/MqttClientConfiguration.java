package info.unterrainer.commons.mqttclient;

import java.util.Optional;
import java.util.function.Function;

import lombok.Getter;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Accessors(fluent = true)
@Getter
public class MqttClientConfiguration {

	private MqttClientConfiguration() {
	}

	private String mqttServer;
	private MqttQos qos;
	private boolean retainOnServer;
	private int dispatchThreads;
	private int dispatchQueueCapacity;
	private SaturationPolicy saturationPolicy;
	private long dispatchShutdownMillis;

	public static MqttClientConfiguration read() {
		return read(null);
	}

	public static MqttClientConfiguration read(final String prefix) {
		return read(prefix, System::getenv);
	}

	static MqttClientConfiguration read(final String prefix, final Function<String, String> env) {
		String p = "";
		if (prefix != null)
			p = prefix;
		MqttClientConfiguration config = new MqttClientConfiguration();

		config.mqttServer = Optional.ofNullable(env.apply(p + "OVERMIND_MQTT_SERVER"))
				.orElse("tcp://10.10.196.4:1883");

		config.qos = parseQos(env.apply(p + "OVERMIND_MQTT_QOS"), MqttQos.AT_LEAST_ONCE);
		config.retainOnServer = parseBoolean(env.apply(p + "OVERMIND_MQTT_RETAIN"), false);

		config.dispatchThreads = parseInt(env.apply(p + "OVERMIND_MQTT_DISPATCH_THREADS"), 4);
		config.dispatchQueueCapacity = parseInt(env.apply(p + "OVERMIND_MQTT_DISPATCH_QUEUE"), 1024);
		config.saturationPolicy = parsePolicy(env.apply(p + "OVERMIND_MQTT_SATURATION_POLICY"),
				SaturationPolicy.DROP_OLDEST);
		config.dispatchShutdownMillis = parseLong(env.apply(p + "OVERMIND_MQTT_DISPATCH_SHUTDOWN_MS"), 5000L);

		return config;
	}

	private static int parseInt(final String value, final int fallback) {
		if (value == null || value.isBlank())
			return fallback;
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			log.warn("Invalid integer value [{}], falling back to [{}].", value, fallback);
			return fallback;
		}
	}

	private static long parseLong(final String value, final long fallback) {
		if (value == null || value.isBlank())
			return fallback;
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException e) {
			log.warn("Invalid long value [{}], falling back to [{}].", value, fallback);
			return fallback;
		}
	}

	private static boolean parseBoolean(final String value, final boolean fallback) {
		if (value == null || value.isBlank())
			return fallback;
		String v = value.trim().toLowerCase();
		if ("true".equals(v) || "1".equals(v) || "yes".equals(v) || "on".equals(v))
			return true;
		if ("false".equals(v) || "0".equals(v) || "no".equals(v) || "off".equals(v))
			return false;
		log.warn("Invalid boolean value [{}], falling back to [{}].", value, fallback);
		return fallback;
	}

	private static SaturationPolicy parsePolicy(final String value, final SaturationPolicy fallback) {
		if (value == null || value.isBlank())
			return fallback;
		try {
			return SaturationPolicy.valueOf(value.trim().toUpperCase());
		} catch (IllegalArgumentException e) {
			log.warn("Invalid saturation policy [{}], falling back to [{}].", value, fallback);
			return fallback;
		}
	}

	private static MqttQos parseQos(final String value, final MqttQos fallback) {
		if (value == null || value.isBlank())
			return fallback;
		String v = value.trim();
		try {
			return MqttQos.valueOf(v.toUpperCase());
		} catch (IllegalArgumentException e) {
			try {
				int mode = Integer.parseInt(v);
				for (MqttQos q : MqttQos.values())
					if (q.getMode() == mode)
						return q;
			} catch (NumberFormatException ignored) {
				// fall through
			}
			log.warn("Invalid QoS value [{}], falling back to [{}].", value, fallback);
			return fallback;
		}
	}
}
