package info.unterrainer.commons.mqttclient;

import java.util.function.BiConsumer;

import org.eclipse.paho.client.mqttv3.IMqttMessageListener;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;

import info.unterrainer.commons.serialization.jsonmapper.JsonMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MqttClient {

	private static final int DEFAULT_DISPATCH_THREADS = 4;
	private static final int DEFAULT_DISPATCH_QUEUE_CAPACITY = 1024;
	private static final SaturationPolicy DEFAULT_SATURATION_POLICY = SaturationPolicy.DROP_OLDEST;
	private static final long DEFAULT_DISPATCH_SHUTDOWN_MILLIS = 5000L;

	private final String mqttServerAddress;
	private final JsonMapper jsonMapper;
	private final long dispatchShutdownMillis;

	private MqttAsyncClient client;
	private final DispatchExecutor dispatchExecutor;

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
		this(clientId, mqttServerAddress, jsonMapper, qos, retainOnServer, DEFAULT_DISPATCH_THREADS,
				DEFAULT_DISPATCH_QUEUE_CAPACITY, DEFAULT_SATURATION_POLICY, DEFAULT_DISPATCH_SHUTDOWN_MILLIS);
	}

	public MqttClient(final String clientId, final MqttClientConfiguration config, final JsonMapper jsonMapper) {
		this(clientId, config.mqttServer(), jsonMapper, config.qos(), config.retainOnServer(),
				config.dispatchThreads(), config.dispatchQueueCapacity(), config.saturationPolicy(),
				config.dispatchShutdownMillis());
	}

	private MqttClient(final String clientId, final String mqttServerAddress, final JsonMapper jsonMapper,
			final MqttQos qos, final boolean retainOnServer, final int dispatchThreads,
			final int dispatchQueueCapacity, final SaturationPolicy saturationPolicy,
			final long dispatchShutdownMillis) {
		this.mqttServerAddress = mqttServerAddress;
		this.jsonMapper = jsonMapper;
		this.qos = qos;
		this.retainOnServer = retainOnServer;
		this.clientId = clientId;
		this.dispatchShutdownMillis = dispatchShutdownMillis;
		try {
			client = new MqttAsyncClient(this.mqttServerAddress, clientId);
		} catch (MqttException e) {
			log.error("Error creating MQTT client.", e);
		}
		client.setCallback(new SimpleMqttCallback());
		this.dispatchExecutor = new DispatchExecutor(clientId, dispatchThreads, dispatchQueueCapacity,
				saturationPolicy);
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
			IMqttToken token = client.connect(options);
			token.waitForCompletion(10_000);
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
			IMqttToken token = client.publish(topic, m);
			token.waitForCompletion();
		} catch (MqttPersistenceException e) {
			log.error("Error persisting MQTT message.", e);
		} catch (MqttException e) {
			log.error("Error sending MQTT message.", e);
		}
	}

	public static boolean topicsMatch(String receivedTopic, String wildCardTopic) {
		return receivedTopic.matches(wildCardTopic.replaceAll("\\+", "[^/]+").replaceAll("#", ".+"));
	}

	/**
	 * Subscribes to {@code topicFilter} and routes incoming messages through the
	 * dispatch executor. The supplied {@code listener} is invoked on a dispatch
	 * thread, never on the Paho I/O thread, so a slow handler on one topic cannot
	 * block delivery on others. With {@code dispatchThreads > 1} the listener may
	 * observe messages on the same topic out of strict arrival order; configure
	 * {@code dispatchThreads = 1} if per-topic ordering is required.
	 */
	public void subscribe(final String topicFilter, final IMqttMessageListener listener) {
		IMqttMessageListener wrapped = (t, m) -> dispatchExecutor.submit(t, () -> {
			try {
				listener.messageArrived(t, m);
			} catch (Exception e) {
				log.error("Listener threw for topic [{}].", t, e);
			}
		});
		try {
			IMqttToken token = client.subscribe(topicFilter, 1, wrapped);
			token.waitForCompletion(5_000);
		} catch (MqttException e) {
			log.error("Error subscribing to topic [{}].", topicFilter);
			log.error("Exception is", e);
		}
	}

	public void unsubscribe(final String topicFilter) {
		try {
			IMqttToken token = client.unsubscribe(topicFilter);
			token.waitForCompletion(5_000);
		} catch (MqttException e) {
			log.error("Error unsubscribing topic [{}].", topicFilter);
			log.error("Exception is", e);
		}
	}

	/**
	 * Subscribes to {@code topic}, parses each payload to {@code type}, and invokes
	 * {@code setter} on the dispatch executor. See
	 * {@link #subscribe(String, IMqttMessageListener)} for ordering and threading
	 * notes.
	 */
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
		if (client.isConnected()) {
			try {
				IMqttToken token = client.disconnect();
				token.waitForCompletion(10_000);
			} catch (MqttException e) {
				log.error("Error disconnecting MQTT client.", e);
			}
		}
		dispatchExecutor.shutdown(dispatchShutdownMillis);
	}

	public long getDroppedDispatchCount() {
		return dispatchExecutor.getDroppedCount();
	}

	public int getDispatchQueueDepth() {
		return dispatchExecutor.getQueueDepth();
	}
}
