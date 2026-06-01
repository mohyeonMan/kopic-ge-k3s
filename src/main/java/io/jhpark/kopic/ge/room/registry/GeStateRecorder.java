package io.jhpark.kopic.ge.room.registry;

import io.jhpark.kopic.ge.common.config.KopicRedisProperties;
import io.jhpark.kopic.ge.common.redis.RedisService;
import io.jhpark.kopic.ge.common.runtime.GeRuntimeState;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeStateRecorder {

	private final RedisService redisService;
	private final KopicRedisProperties redisProperties;
	private final GeRuntimeState runtimeState;
	private final AtomicBoolean recording = new AtomicBoolean(true);

	@Scheduled(
		fixedDelayString = "${kopic.redis.heartbeat-interval:10s}",
		initialDelayString = "${kopic.redis.initial-delay:2s}"
	)
	public void heartbeat() {
		runStatusUpdate("heartbeat", () -> {
			String status = runtimeState.statusValue();
			redisService.set(
				redisProperties.geKey(geId()),
				status,
				redisProperties.heartbeatTtl()
			);
			log.debug("ge heartbeat refreshed. geId={}, status={}", geId(), status);
		});
	}

	@Scheduled(
		fixedDelayString = "${kopic.redis.load-interval:1m}",
		initialDelayString = "${kopic.redis.initial-delay:2s}"
	)
	public void reportLoad() {
		runStatusUpdate("report-load", () -> {
			if (runtimeState.isDraining()) {
				redisService.zRemove(redisProperties.keys().geLoad(), geId());
				log.debug("ge load removed because ge is draining. geId={}", geId());
				return;
			}
			int rooms = runtimeState.roomCount();
			int participants = runtimeState.participantCount();
			double loadScore = runtimeState.loadScore(redisProperties.roomWeight());
			redisService.zAdd(redisProperties.keys().geLoad(), geId(), loadScore);
			log.debug("ge load refreshed. geId={}, roomCount={}, participantCount={}, score={}",
				geId(), rooms, participants, loadScore);
		});
	}

	public void stopRecording() {
		recording.set(false);
	}

	private void runStatusUpdate(String action, Runnable update) {
		if (!recording.get()) {
			return;
		}
		if (!redisProperties.enabled()) {
			return;
		}
		try {
			update.run();
		} catch (RuntimeException runtimeException) {
			log.warn("ge status update failed. action={}, geId={}, error={}",
				action,
				geId(),
				runtimeException.getMessage());
			log.debug("ge status update failure detail. action={}, geId={}", action, geId(), runtimeException);
		}
	}

	private String geId() {
		return runtimeState.geId();
	}
}
