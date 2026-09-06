package info.unterrainer.commons.mqttclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

public class DispatchExecutorTests {

	private CapturingAppender appender;
	private LoggerConfig loggerConfig;
	private LoggerContext context;

	@BeforeEach
	public void setUp() {
		context = (LoggerContext) LogManager.getContext(false);
		Configuration config = context.getConfiguration();
		loggerConfig = config.getLoggerConfig(DispatchExecutor.class.getName());
		appender = new CapturingAppender("dispatch-test-appender");
		appender.start();
		config.addAppender(appender);
		loggerConfig.addAppender(appender, Level.WARN, null);
		context.updateLoggers();
	}

	@AfterEach
	public void tearDown() {
		loggerConfig.removeAppender(appender.getName());
		appender.stop();
		context.updateLoggers();
	}

	@Test
	public void slowListenerDoesNotBlockOtherTopic() throws Exception {
		DispatchExecutor exec = new DispatchExecutor("test-iso", 2, 16, SaturationPolicy.DROP_OLDEST);
		try {
			CountDownLatch slowStarted = new CountDownLatch(1);
			CountDownLatch fastDone = new CountDownLatch(1);
			AtomicLong fastFiredAtMillis = new AtomicLong();

			long submitMillis = System.currentTimeMillis();
			exec.submit("A", () -> {
				slowStarted.countDown();
				try {
					Thread.sleep(5_000);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			});
			assertThat(slowStarted.await(1, TimeUnit.SECONDS)).isTrue();

			exec.submit("B", () -> {
				fastFiredAtMillis.set(System.currentTimeMillis());
				fastDone.countDown();
			});

			assertThat(fastDone.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(fastFiredAtMillis.get() - submitMillis).isLessThan(1_000);
		} finally {
			exec.shutdown(100);
		}
	}

	@Test
	public void sameTopicTasksNeverRunConcurrentlyAndStayInOrder() throws Exception {
		DispatchExecutor exec = new DispatchExecutor("test-seq", 4, 16, SaturationPolicy.DROP_OLDEST);
		try {
			List<Integer> order = Collections.synchronizedList(new ArrayList<>());
			CountDownLatch firstStarted = new CountDownLatch(1);
			CountDownLatch releaseFirst = new CountDownLatch(1);
			CountDownLatch secondDone = new CountDownLatch(1);
			AtomicBoolean secondObservedFirstStillRunning = new AtomicBoolean(false);

			exec.submit("A", () -> {
				order.add(1);
				firstStarted.countDown();
				try {
					releaseFirst.await(2, TimeUnit.SECONDS);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				order.add(2);
			});
			assertThat(firstStarted.await(1, TimeUnit.SECONDS)).isTrue();

			exec.submit("A", () -> {
				// If the mailbox let this run before the first task finished, order
				// would only contain [1] here instead of [1, 2].
				if (!order.equals(List.of(1, 2)))
					secondObservedFirstStillRunning.set(true);
				order.add(3);
				secondDone.countDown();
			});

			// Give a broken (non-ordered) scheduler a real chance to start task 2 early;
			// with 4 dispatch threads free, nothing but correct mailbox semantics stops it.
			Thread.sleep(200);
			releaseFirst.countDown();

			assertThat(secondDone.await(2, TimeUnit.SECONDS)).isTrue();
			assertThat(secondObservedFirstStillRunning.get()).isFalse();
			assertThat(order).containsExactly(1, 2, 3);
		} finally {
			exec.shutdown(100);
		}
	}

	@Test
	public void differentTopicsRunGenuinelyConcurrently() throws Exception {
		DispatchExecutor exec = new DispatchExecutor("test-concurrent-topics", 2, 16, SaturationPolicy.DROP_OLDEST);
		CyclicBarrier rendezvous = new CyclicBarrier(2);
		try {
			CountDownLatch done = new CountDownLatch(2);
			Runnable meetAtBarrier = () -> {
				try {
					rendezvous.await(2, TimeUnit.SECONDS);
				} catch (Exception e) {
					throw new RuntimeException(e);
				}
				done.countDown();
			};

			// Both handlers block until the other also arrives. This can only succeed if
			// they run at the same time on different threads - proving the two topics'
			// mailboxes are independently concurrent, not merely non-blocking.
			exec.submit("A", meetAtBarrier);
			exec.submit("B", meetAtBarrier);

			assertThat(done.await(2, TimeUnit.SECONDS)).isTrue();
		} finally {
			exec.shutdown(2_000);
		}
	}

	@Test
	public void floodingTopicSaturationDoesNotAffectOtherTopic() throws Exception {
		DispatchExecutor exec = new DispatchExecutor("test-flood-isolation", 2, 4, SaturationPolicy.DROP_OLDEST);
		CountDownLatch gate = new CountDownLatch(1);
		try {
			// Occupy topic A's drain thread so its own mailbox queue backs up.
			exec.submit("A", () -> {
				try {
					gate.await();
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			});
			// Flood topic A well past its own mailbox capacity (4).
			for (int i = 0; i < 20; i++)
				exec.submit("A", () -> {
				});

			// Topic B must still be delivered promptly and completely, unaffected by A's flood.
			CountDownLatch bDone = new CountDownLatch(1);
			AtomicLong bFiredAtMillis = new AtomicLong();
			long submitMillis = System.currentTimeMillis();
			exec.submit("B", () -> {
				bFiredAtMillis.set(System.currentTimeMillis());
				bDone.countDown();
			});

			assertThat(bDone.await(1, TimeUnit.SECONDS)).isTrue();
			assertThat(bFiredAtMillis.get() - submitMillis).isLessThan(1_000);
			assertThat(exec.getDroppedCount()).isGreaterThan(0L);
		} finally {
			gate.countDown();
			exec.shutdown(2_000);
		}
	}

	@Test
	public void dropOldestOnSaturationCountsAndWarnsOncePerTopic() throws Exception {
		DispatchExecutor exec = new DispatchExecutor("test-drop", 1, 4, SaturationPolicy.DROP_OLDEST);
		CountDownLatch gate = new CountDownLatch(1);
		try {
			// Occupy the single thread so subsequent submits queue up.
			exec.submit("A", () -> {
				try {
					gate.await();
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			});

			for (int i = 0; i < 20; i++)
				exec.submit("A", () -> {
				});

			assertThat(exec.getDroppedCount()).isGreaterThan(0L);
			assertThat(warnCountForTopic("A")).isEqualTo(1L);
		} finally {
			gate.countDown();
			exec.shutdown(2_000);
		}
	}

	@Test
	public void blockPolicyAppliesBackPressure() throws Exception {
		DispatchExecutor exec = new DispatchExecutor("test-block", 1, 2, SaturationPolicy.BLOCK);
		CountDownLatch gate = new CountDownLatch(1);
		try {
			// Fill running slot.
			exec.submit("A", () -> {
				try {
					gate.await();
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
			});
			// Fill queue (capacity 2).
			exec.submit("A", () -> {
			});
			exec.submit("A", () -> {
			});

			Thread blocker = new Thread(() -> exec.submit("A", () -> {
			}), "block-test-submitter");
			blocker.start();
			Thread.sleep(200);
			assertThat(blocker.isAlive()).as("submit must block while queue is full").isTrue();

			gate.countDown();
			blocker.join(2_000);
			assertThat(blocker.isAlive()).as("submit unblocks after capacity frees").isFalse();
		} finally {
			gate.countDown();
			exec.shutdown(2_000);
		}
	}

	@Test
	public void disconnectDrainsHandlersWithinBudget() throws Exception {
		DispatchExecutor exec = new DispatchExecutor("test-drain", 2, 16, SaturationPolicy.DROP_OLDEST);
		CountDownLatch all = new CountDownLatch(3);
		for (int i = 0; i < 3; i++)
			exec.submit("A", () -> {
				try {
					Thread.sleep(200);
				} catch (InterruptedException ignored) {
					Thread.currentThread().interrupt();
				}
				all.countDown();
			});

		long start = System.currentTimeMillis();
		int abandoned = exec.shutdown(5_000);
		long elapsed = System.currentTimeMillis() - start;

		assertThat(abandoned).isZero();
		assertThat(elapsed).isLessThan(5_000);
		assertThat(all.getCount()).isZero();
	}

	@Test
	public void disconnectBoundsWaitWhenHandlerHangs() throws Exception {
		DispatchExecutor exec = new DispatchExecutor("test-hang", 1, 8, SaturationPolicy.DROP_OLDEST);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		exec.submit("A", () -> {
			start.countDown();
			try {
				release.await();
			} catch (InterruptedException ignored) {
				Thread.currentThread().interrupt();
			}
		});
		assertThat(start.await(1, TimeUnit.SECONDS)).isTrue();

		// Queue extra tasks that will be abandoned at shutdownNow.
		for (int i = 0; i < 3; i++)
			exec.submit("A", () -> {
			});

		long t0 = System.currentTimeMillis();
		int abandoned = exec.shutdown(500);
		long elapsed = System.currentTimeMillis() - t0;

		try {
			assertThat(elapsed).isLessThan(1_500);
			assertThat(abandoned).isGreaterThan(0);
			assertThat(warnLogContains("abandoned")).isTrue();
		} finally {
			release.countDown();
		}
	}

	private long warnCountForTopic(final String topic) {
		List<LogEvent> snapshot;
		synchronized (appender.events) {
			snapshot = new ArrayList<>(appender.events);
		}
		return snapshot.stream()
				.filter(e -> e.getLevel() == Level.WARN)
				.map(e -> e.getMessage().getFormattedMessage())
				.filter(m -> m.contains("Dispatch saturation"))
				.filter(m -> m.contains("[" + topic + "]"))
				.count();
	}

	private boolean warnLogContains(final String fragment) {
		List<LogEvent> snapshot;
		synchronized (appender.events) {
			snapshot = new ArrayList<>(appender.events);
		}
		return snapshot.stream()
				.filter(e -> e.getLevel() == Level.WARN)
				.map(e -> e.getMessage().getFormattedMessage())
				.anyMatch(m -> m.contains(fragment));
	}

	private static final class CapturingAppender extends AbstractAppender {
		final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());

		CapturingAppender(final String name) {
			super(name, null, null, true, null);
		}

		@Override
		public void append(final LogEvent event) {
			events.add(event.toImmutable());
		}
	}
}
