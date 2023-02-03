package info.unterrainer.commons.mqttclient;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class MqttClientMatcherTests {

	@Test
	public void fullMatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-11/sensor:0/relay",
				"shellies/192-168-16-11/sensor:0/relay");
		assertThat(result).isTrue();
	}

	@Test
	public void fullMismatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-11/sensor:0/relay",
				"shellies/192-168-16-11/sensor:1/relay");
		assertThat(result).isFalse();
	}

	@Test
	public void wildcardAllMatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-11/sensor:0/relay", "shellies/192-168-16-11/#");
		assertThat(result).isTrue();
	}

	@Test
	public void wildcardAllExactMismatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-11", "shellies/192-168-16-11/#");
		assertThat(result).isFalse();
	}

	@Test
	public void wildcardAllMismatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-9/sensor:0/relay", "shellies/192-168-16-11/#");
		assertThat(result).isFalse();
	}

	@Test
	public void wildcardLevelMatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-11/sensor:0/relay",
				"shellies/192-168-16-11/+/relay");
		assertThat(result).isTrue();
	}

	@Test
	public void wildcardLevelExactMismatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-11", "shellies/192-168-16-11/+");
		assertThat(result).isFalse();
	}

	@Test
	public void wildcardLevelPostMismatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-11/sensor:0/relay",
				"shellies/192-168-16-11/+/blah");
		assertThat(result).isFalse();
	}

	@Test
	public void wildcardLevelPreMismatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-9/sensor:0/relay",
				"shellies/192-168-16-11/+/relay");
		assertThat(result).isFalse();
	}

	@Test
	public void wildcardBothMatchWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-11/sensor:0/relay", "shellies/+/sensor:0/#");
		assertThat(result).isTrue();
	}

	@Test
	public void wildcardBothMismatchPreWorks() {
		boolean result = MqttClient.topicsMatch("ddd/192-168-16-11/sensor:0/relay", "shellies/+/sensor:0/#");
		assertThat(result).isFalse();
	}

	@Test
	public void wildcardBothMismatchMiddleWorks() {
		boolean result = MqttClient.topicsMatch("shellies/192-168-16-11/sensor/relay", "shellies/+/sensor:0/#");
		assertThat(result).isFalse();
	}
}
