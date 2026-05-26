package io.jhpark.kopic.ge.room.directory;

import io.jhpark.kopic.ge.common.config.KopicRedisProperties;
import io.jhpark.kopic.ge.common.config.NodeProperties;
import io.jhpark.kopic.ge.common.redis.RedisService;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeStateRecorder {

	private static final String DRAINING = "DRAINING";

	private final AtomicInteger roomCount = new AtomicInteger();
	private final AtomicInteger participantCount = new AtomicInteger();

	private final RedisService redisService;
	private final KopicRedisProperties redisProperties;
	private final NodeProperties nodeProperties;

	public void recordRoomCreated() {
		roomCount.incrementAndGet();
	}

	public void recordRoomClosed() {
		decrement(roomCount);
	}

	public void recordParticipantJoined() {
		participantCount.incrementAndGet();
	}

	public void recordParticipantLeft() {
		decrement(participantCount);
	}

	public void reconcile(int actualRoomCount, int actualParticipantCount) {
		roomCount.set(Math.max(0, actualRoomCount));
		participantCount.set(Math.max(0, actualParticipantCount));
		log.debug("ge runtime state reconciled. geId={}, roomCount={}, participantCount={}",
			geId(), roomCount.get(), participantCount.get());
	}

	@Scheduled(
		fixedDelayString = "${kopic.redis.heartbeat-interval:10s}",
		initialDelayString = "${kopic.redis.initial-delay:2s}"
	)
	public void heartbeat() {
		runStatusUpdate("heartbeat", () -> {
			redisService.set(
				redisProperties.geKey(geId()),
				status(),
				redisProperties.heartbeatTtl()
			);
			log.debug("ge heartbeat refreshed. geId={}, status={}", geId(), status());
		});
	}

	@Scheduled(
		fixedDelayString = "${kopic.redis.load-interval:1m}",
		initialDelayString = "${kopic.redis.initial-delay:2s}"
	)
	public void reportLoad() {
		runStatusUpdate("report-load", () -> {
			int rooms = roomCount.get();
			int participants = participantCount.get();
			double loadScore = participants + rooms * redisProperties.roomWeight();
			redisService.zAdd(redisProperties.keys().geLoad(), geId(), loadScore);
			log.debug("ge load refreshed. geId={}, roomCount={}, participantCount={}, score={}",
				geId(), rooms, participants, loadScore);
		});
	}

	@PreDestroy
	public void markDraining() {
		if (!redisProperties.enabled()) {
			return;
		}
		try {
			redisService.set(
				redisProperties.geKey(geId()),
				DRAINING,
				redisProperties.heartbeatTtl()
			);
			redisService.zRemove(redisProperties.keys().geLoad(), geId());
			log.info("ge marked as draining. geId={}", geId());
		} catch (RuntimeException runtimeException) {
			log.debug("ge draining marker skipped. geId={}, error={}", geId(), runtimeException.getMessage());
		}
	}

	private void runStatusUpdate(String action, Runnable update) {
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

	private void decrement(AtomicInteger counter) {
		counter.updateAndGet(value -> Math.max(0, value - 1));
	}

	private String status() {
		return redisProperties.status();
	}

	private String geId() {
		return nodeProperties.nodeId();
	}
}
