package info.unterrainer.commons.mqttclient;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import lombok.extern.slf4j.Slf4j;

@Slf4j
class DispatchExecutor {

	private static final long WARN_THROTTLE_NANOS = TimeUnit.SECONDS.toNanos(5);

	private final ThreadPoolExecutor executor;
	private final SaturationPolicy policy;
	private final int mailboxCapacity;
	private final AtomicLong droppedCount = new AtomicLong();
	private final ConcurrentHashMap<String, Long> lastWarnNanos = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, TopicMailbox> mailboxes = new ConcurrentHashMap<>();

	DispatchExecutor(final String clientId, final int dispatchThreads, final int dispatchQueueCapacity,
			final SaturationPolicy policy) {
		this.policy = policy;
		this.mailboxCapacity = dispatchQueueCapacity;
		ThreadFactory factory = new NamedThreadFactory("mqtt-dispatch-" + clientId);
		// Unbounded: this queue only ever holds one "drain" trigger per topic that
		// currently has in-flight work, never the messages themselves - those live in
		// each topic's own bounded TopicMailbox queue (see submit()/TopicMailbox).
		this.executor = new ThreadPoolExecutor(dispatchThreads, dispatchThreads, 0L, TimeUnit.MILLISECONDS,
				new LinkedBlockingQueue<>(), factory);
		this.executor.prestartAllCoreThreads();
	}

	void submit(final String topic, final Runnable task) {
		final String safeTopic = topic == null ? "" : topic;
		Runnable wrapped = () -> {
			try {
				task.run();
			} catch (Throwable t) {
				log.error("Dispatch handler threw for topic [{}]", safeTopic, t);
			}
		};
		while (true) {
			TopicMailbox mailbox = mailboxes.computeIfAbsent(safeTopic, TopicMailbox::new);
			if (mailbox.offer(wrapped))
				return;
			// mailbox finished draining and removed itself between computeIfAbsent and
			// offer(); retry, which creates a fresh mailbox for the topic.
		}
	}

	long getDroppedCount() {
		return droppedCount.get();
	}

	int getQueueDepth() {
		int total = 0;
		for (TopicMailbox mailbox : mailboxes.values())
			total += mailbox.size();
		return total;
	}

	int shutdown(final long timeoutMillis) {
		executor.shutdown();
		try {
			if (executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS))
				return 0;
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
		executor.shutdownNow();
		int abandoned = 0;
		for (TopicMailbox mailbox : mailboxes.values())
			abandoned += mailbox.size();
		if (abandoned > 0)
			log.warn("Dispatch executor shutdown abandoned [{}] tasks.", abandoned);
		return abandoned;
	}

	private void logWarnThrottled(final String topic, final String policyName) {
		long now = System.nanoTime();
		AtomicBoolean fire = new AtomicBoolean(false);
		lastWarnNanos.compute(topic, (k, prev) -> {
			if (prev == null || (now - prev) >= WARN_THROTTLE_NANOS) {
				fire.set(true);
				return now;
			}
			return prev;
		});
		if (fire.get())
			log.warn("Dispatch saturation: dropping task for topic [{}] under policy [{}]", topic, policyName);
	}

	/**
	 * A strictly-FIFO, single-drain-at-a-time mailbox for one concrete MQTT
	 * topic. At most one drain loop runs per mailbox at any time, so tasks
	 * submitted for the same topic can never run concurrently or out of order,
	 * while distinct topics' mailboxes drain independently on the shared pool.
	 */
	private final class TopicMailbox {

		private final String topic;
		private final Queue<Runnable> queue = new ArrayDeque<>();
		private boolean active;
		private boolean closed;

		private TopicMailbox(final String topic) {
			this.topic = topic;
		}

		/**
		 * @return {@code true} if the task was accepted (queued, dropped or blocked
		 *         per policy) by this mailbox; {@code false} if this mailbox has
		 *         already finished draining and removed itself, meaning the caller
		 *         must retry against a freshly created mailbox for the same topic.
		 */
		synchronized boolean offer(final Runnable task) {
			if (closed)
				return false;
			if (policy == SaturationPolicy.BLOCK) {
				while (!closed && queue.size() >= mailboxCapacity) {
					try {
						wait();
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						return true;
					}
				}
				if (closed)
					return false;
				enqueue(task);
				return true;
			}
			if (queue.size() >= mailboxCapacity)
				applySaturationPolicy(task);
			else
				enqueue(task);
			return true;
		}

		synchronized int size() {
			return queue.size();
		}

		private void enqueue(final Runnable task) {
			queue.add(task);
			if (!active) {
				active = true;
				executor.execute(this::drain);
			}
		}

		private void applySaturationPolicy(final Runnable task) {
			switch (policy) {
			case DROP_NEWEST -> {
				droppedCount.incrementAndGet();
				logWarnThrottled(topic, "DROP_NEWEST");
			}
			case DROP_OLDEST -> {
				Runnable head = queue.poll();
				if (head != null) {
					droppedCount.incrementAndGet();
					logWarnThrottled(topic, "DROP_OLDEST");
				}
				queue.add(task);
			}
			case BLOCK -> throw new IllegalStateException("BLOCK is handled in offer() before capacity checks.");
			}
		}

		private void drain() {
			while (true) {
				if (Thread.currentThread().isInterrupted())
					return;
				Runnable task;
				synchronized (this) {
					task = queue.poll();
					if (task == null) {
						active = false;
						closed = true;
						mailboxes.remove(topic, this);
						notifyAll();
						return;
					}
					notifyAll();
				}
				task.run();
			}
		}
	}

	private static final class NamedThreadFactory implements ThreadFactory {
		private final String prefix;
		private final AtomicInteger counter = new AtomicInteger();

		private NamedThreadFactory(final String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(final Runnable r) {
			Thread t = new Thread(r, prefix + "-" + counter.incrementAndGet());
			t.setDaemon(true);
			return t;
		}
	}
}
