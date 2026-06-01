package io.jhpark.kopic.ge.common.lifecycle;

import io.jhpark.kopic.ge.common.config.KopicRedisProperties;
import io.jhpark.kopic.ge.common.redis.RedisService;
import io.jhpark.kopic.ge.common.runtime.GeRuntimeState;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeDrainService implements SmartLifecycle {

	private static final Duration DRAIN_TEST_WAIT = Duration.ofSeconds(10);

	private final RedisService redisService;
	private final KopicRedisProperties redisProperties;
	private final GeRuntimeState runtimeState;
	private final AtomicBoolean running = new AtomicBoolean(false);

	@Override
	public void start() {
		running.set(true);
		log.info("ge drain lifecycle started. phase={}, geId={}", getPhase(), runtimeState.geId());
	}

	@Override
	public void stop(Runnable callback) {
		try {
			log.info("ge drain lifecycle stop started. phase={}, geId={}, status={}",
				getPhase(), runtimeState.geId(), runtimeState.statusValue());
			boolean transitioned = runtimeState.enterDrain();
			log.info("ge drain lifecycle entered drain. geId={}, transitioned={}, status={}",
				runtimeState.geId(), transitioned, runtimeState.statusValue());
			publishDrainState();
			waitForDrainTest();
			running.set(false);
			log.info("ge drain lifecycle stop completed. phase={}, geId={}", getPhase(), runtimeState.geId());
		} finally {
			callback.run();
		}
	}

	@Override
	public void stop() {
		stop(() -> {
		});
	}

	@Override
	public boolean isRunning() {
		return running.get();
	}

	@Override
	public int getPhase() {
		return LifecyclePhases.GE_DRAIN;
	}

	@Override
	public boolean isAutoStartup() {
		return true;
	}

	private void publishDrainState() {
		if (!redisProperties.enabled()) {
			log.info("ge drain registry update skipped. geId={}, reason=redis-disabled", runtimeState.geId());
			return;
		}
		try {
			redisService.set(
				redisProperties.geKey(runtimeState.geId()),
				runtimeState.statusValue(),
				redisProperties.heartbeatTtl()
			);
			redisService.zRemove(redisProperties.keys().geLoad(), runtimeState.geId());
			log.info("ge drain registry updated. geId={}, status={}", runtimeState.geId(), runtimeState.statusValue());
		} catch (RuntimeException runtimeException) {
			log.warn("ge drain registry update failed. geId={}, error={}",
				runtimeState.geId(), runtimeException.getMessage());
			log.debug("ge drain registry update failure detail. geId={}", runtimeState.geId(), runtimeException);
		}
	}

	private void waitForDrainTest() {
		log.info("ge drain lifecycle waiting before callback. geId={}, duration={}",
			runtimeState.geId(), DRAIN_TEST_WAIT);
		try {
			Thread.sleep(DRAIN_TEST_WAIT.toMillis());
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
			log.warn("ge drain lifecycle wait interrupted. geId={}", runtimeState.geId());
		}
		log.info("ge drain lifecycle wait completed. geId={}", runtimeState.geId());
	}
}
