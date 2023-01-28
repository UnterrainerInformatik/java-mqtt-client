package info.unterrainer.commons.mqttclient;

import java.util.function.BiConsumer;

import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;

import info.unterrainer.commons.serialization.jsonmapper.JsonMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MqttClient {
	private final String mqttServerAddress;
	private final JsonMapper jsonMapper;

	private org.eclipse.paho.client.mqttv3.MqttClient client;

	@Getter
	private String clientId;
	@Getter
	private final MqttQos qos;
	@Getter
	private final boolean retainOnServer;

	public MqttClient(final String clientId, final String mqttServerAddress, final JsonMapper jsonMapper) {
		this(clientId, mqttServerAddress, jsonMapper, MqttQos.AT_LEAST_ONCE, false);
	}

	public MqttClient(final String clientId, final String mqttServerAddress, final JsonMapper jsonMapper,
			final boolean retainOnServer) {
		this(clientId, mqttServerAddress, jsonMapper, MqttQos.AT_LEAST_ONCE, retainOnServer);
	}

	public MqttClient(final String clientId, final String mqttServerAddress, final JsonMapper jsonMapper,
			final MqttQos qos) {
		this(clientId, mqttServerAddress, jsonMapper, qos, false);
	}

	public MqttClient(final String clientId, final String mqttServerAddress, final JsonMapper jsonMapper,
			final MqttQos qos, final boolean retainOnServer) {
		this.mqttServerAddress = mqttServerAddress;
		this.jsonMapper = jsonMapper;
		this.qos = qos;
		this.retainOnServer = retainOnServer;
		this.clientId = clientId;
		try {
			client = new org.eclipse.paho.client.mqttv3.MqttClient(this.mqttServerAddress, clientId);
		} catch (MqttException e) {
			log.error("Error creating MQTT client.", e);
		}
		client.setCallback(new SimpleMqttCallback());
		connect();
	}

	/**
	 * Connects to the given server if it's disconnected. Immediately returns
	 * otherwise.
	 */
	public synchronized void connect() {
		if (client.isConnected())
			return;
		try {
			MqttConnectOptions options = new MqttConnectOptions();
			options.setAutomaticReconnect(true);
			options.setCleanSession(false);
			options.setConnectionTimeout(10);
			client.connect(options);
		} catch (MqttException e) {
			log.error("Error connecting MQTT client.", e);
		}
	}

	public void send(final String topic, final String message) {
		send(topic, message, MqttQos.AT_LEAST_ONCE, false);
	}

	public void send(final String topic, final String message, final MqttQos qos) {
		send(topic, message, qos, false);
	}

	public void send(final String topic, final String message, final boolean retainOnServer) {
		send(topic, message, MqttQos.AT_LEAST_ONCE, retainOnServer);
	}

	/**
	 * Sends a string to a given topic. Tries to connect, if the client isn't
	 * connected.
	 *
	 * @param topic          the topic to send to.
	 * @param message        the message to send.
	 * @param qos            specifies how the message will be sent.
	 * @param retainOnServer if set, the server will send this message (if it's the
	 *                       last in a topic) automatically to any newly connected
	 *                       client.
	 */
	public void send(final String topic, final String message, final MqttQos qos, final boolean retainOnServer) {
		connect();
		MqttMessage m = new MqttMessage();
		m.setPayload(message.getBytes());
		m.setQos(qos.getMode());
		m.setRetained(retainOnServer);
		try {
			client.publish(topic, m);
		} catch (MqttPersistenceException e) {
			log.error("Error persisting MQTT message.", e);
		} catch (MqttException e) {
			log.error("Error sending MQTT message.", e);
		}
	}

	public void subscribe(final String topicFilter, final IMqttMessageListener listener) {
		try {
			client.subscribe(topicFilter, listener);
		} catch (MqttException e) {
			log.error("Error subscribing to topic [{}].", topicFilter);
			log.error("Exception is", e);
		}
	}

	public void unsubscribe(final String topicFilter) {
		try {
			client.unsubscribe(topicFilter);
		} catch (MqttException e) {
			log.error("Error unsubscribing topic [{}].", topicFilter);
			log.error("Exception is", e);
		}
	}

	@SuppressWarnings("unchecked")
	public <T> void subscribe(final String topic, final Class<?> type, final BiConsumer<String, T> setter) {

		subscribe(topic, (actualTopic, actualMessage) -> {
			try {
				String stringValue = new String(actualMessage.getPayload());
				Object v = stringValue;
				log.info("subscription fired for topic [{}] with value [{}]", actualTopic, stringValue);

				try {
					v = jsonMapper.fromStringTo(type, stringValue);
				} catch (Exception e) {
					try {
						if (type == Integer.class)
							v = Integer.parseInt(stringValue);
						if (type == Long.class)
							v = Long.parseLong(stringValue);
						if (type == Float.class)
							v = Float.parseFloat(stringValue);
						if (type == Double.class)
							v = Double.parseDouble(stringValue);
						if (type == Boolean.class)
							if ("1".equals(stringValue.trim()) || "on".equalsIgnoreCase(stringValue.trim())
									|| "true".equalsIgnoreCase(stringValue.trim())
									|| "open".equalsIgnoreCase(stringValue.trim()))
								v = true;
							else if ("0".equals(stringValue.trim()) || "off".equalsIgnoreCase(stringValue.trim())
									|| "false".equalsIgnoreCase(stringValue.trim())
									|| "close".equalsIgnoreCase(stringValue.trim())
									|| "overpower".equalsIgnoreCase(stringValue.trim()))
								v = false;
							else
								v = Boolean.parseBoolean(stringValue);
					} catch (NumberFormatException e1) {
						log.warn("Error parsing to type [{}]. Falling back to string.", type.getSimpleName());
						// Fallback is String.
						setter.accept(actualTopic, (T) String.class.cast(v));
						return;
					}
				}
				setter.accept(actualTopic, (T) type.cast(v));
			} catch (Throwable e) {
				log.error("Exception in MQTT subscription.", e);
			}
		});
	}

	/**
	 * Disconnects from the given server after finishing all pending work. If the
	 * client wasn't connected in the first place, this method returns immediately.
	 */
	public void disconnect() {
		if (!client.isConnected())
			return;
		try {
			client.disconnect();
		} catch (MqttException e) {
			log.error("Error disconnecting MQTT client.", e);
		}
	}
}
