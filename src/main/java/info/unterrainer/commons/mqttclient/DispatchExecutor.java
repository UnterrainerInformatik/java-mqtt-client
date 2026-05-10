package info.unterrainer.commons.mqttclient;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionHandler;
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
	private final AtomicLong droppedCount = new AtomicLong();
	private final ConcurrentHashMap<String, Long> lastWarnNanos = new ConcurrentHashMap<>();

	DispatchExecutor(final String clientId, final int dispatchThreads, final int dispatchQueueCapacity,
			final SaturationPolicy policy) {
		this.policy = policy;
		BlockingQueue<Runnable> queue = new ArrayBlockingQueue<>(dispatchQueueCapacity);
		ThreadFactory factory = new NamedThreadFactory("mqtt-dispatch-" + clientId);
		this.executor = new ThreadPoolExecutor(dispatchThreads, dispatchThreads, 0L, TimeUnit.MILLISECONDS, queue,
				factory, buildHandler(policy));
		this.executor.prestartAllCoreThreads();
	}

	void submit(final String topic, final Runnable task) {
		final String safeTopic = topic == null ? "" : topic;
		Runnable wrapped = new TopicTask(safeTopic, () -> {
			try {
				task.run();
			} catch (Throwable t) {
				log.error("Dispatch handler threw for topic [{}]", safeTopic, t);
			}
		});
		executor.execute(wrapped);
	}

	long getDroppedCount() {
		return droppedCount.get();
	}

	int getQueueDepth() {
		return executor.getQueue().size();
	}

	int shutdown(final long timeoutMillis) {
		executor.shutdown();
		try {
			if (executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS))
				return 0;
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
		List<Runnable> abandoned = executor.shutdownNow();
		int count = abandoned.size();
		if (count > 0)
			log.warn("Dispatch executor shutdown abandoned [{}] tasks.", count);
		return count;
	}

	private RejectedExecutionHandler buildHandler(final SaturationPolicy p) {
		return (r, ex) -> {
			String topic = (r instanceof TopicTask tt) ? tt.topic : "";
			switch (p) {
			case DROP_NEWEST -> {
				droppedCount.incrementAndGet();
				logWarnThrottled(topic, "DROP_NEWEST");
			}
			case DROP_OLDEST -> {
				Runnable head = ex.getQueue().poll();
				if (head != null) {
					droppedCount.incrementAndGet();
					String victim = (head instanceof TopicTask vt) ? vt.topic : topic;
					logWarnThrottled(victim, "DROP_OLDEST");
				}
				if (!ex.getQueue().offer(r))
					droppedCount.incrementAndGet();
			}
			case BLOCK -> {
				try {
					ex.getQueue().put(r);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
				}
			}
			}
		};
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

	private static final class TopicTask implements Runnable {
		private final String topic;
		private final Runnable delegate;

		private TopicTask(final String topic, final Runnable delegate) {
			this.topic = topic;
			this.delegate = delegate;
		}

		@Override
		public void run() {
			delegate.run();
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
