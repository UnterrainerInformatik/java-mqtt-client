package info.unterrainer.commons.mqttclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

public class MqttClientConfigurationTests {

	@Test
	public void defaultsApplyWhenNoEnv() {
		MqttClientConfiguration config = MqttClientConfiguration.read(null, k -> null);

		assertThat(config.dispatchThreads()).isEqualTo(4);
		assertThat(config.dispatchQueueCapacity()).isEqualTo(1024);
		assertThat(config.saturationPolicy()).isEqualTo(SaturationPolicy.DROP_OLDEST);
		assertThat(config.dispatchShutdownMillis()).isEqualTo(5000L);
	}

	@Test
	public void envVarsOverrideDispatchSettings() {
		Map<String, String> env = new HashMap<>();
		env.put("OVERMIND_MQTT_DISPATCH_THREADS", "8");
		env.put("OVERMIND_MQTT_DISPATCH_QUEUE", "256");
		env.put("OVERMIND_MQTT_SATURATION_POLICY", "BLOCK");
		env.put("OVERMIND_MQTT_DISPATCH_SHUTDOWN_MS", "12345");

		MqttClientConfiguration config = MqttClientConfiguration.read(null, env::get);

		assertThat(config.dispatchThreads()).isEqualTo(8);
		assertThat(config.dispatchQueueCapacity()).isEqualTo(256);
		assertThat(config.saturationPolicy()).isEqualTo(SaturationPolicy.BLOCK);
		assertThat(config.dispatchShutdownMillis()).isEqualTo(12345L);
	}

	@Test
	public void prefixIsAppliedToEnvLookups() {
		Map<String, String> env = new HashMap<>();
		env.put("FOO_OVERMIND_MQTT_DISPATCH_THREADS", "16");
		env.put("FOO_OVERMIND_MQTT_SATURATION_POLICY", "DROP_NEWEST");

		MqttClientConfiguration config = MqttClientConfiguration.read("FOO_", env::get);

		assertThat(config.dispatchThreads()).isEqualTo(16);
		assertThat(config.saturationPolicy()).isEqualTo(SaturationPolicy.DROP_NEWEST);
	}

	@Test
	public void invalidValuesFallBackToDefaults() {
		Map<String, String> env = new HashMap<>();
		env.put("OVERMIND_MQTT_DISPATCH_THREADS", "not-a-number");
		env.put("OVERMIND_MQTT_SATURATION_POLICY", "NONSENSE");

		MqttClientConfiguration config = MqttClientConfiguration.read(null, env::get);

		assertThat(config.dispatchThreads()).isEqualTo(4);
		assertThat(config.saturationPolicy()).isEqualTo(SaturationPolicy.DROP_OLDEST);
	}
}
