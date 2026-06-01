package io.jhpark.kopic.ge.common.lifecycle;

import io.jhpark.kopic.ge.common.config.DrainProperties;
import io.jhpark.kopic.ge.common.runtime.GeRuntimeState;
import io.jhpark.kopic.ge.room.registry.DefaultQuickRoomCandidateStore;
import io.jhpark.kopic.ge.room.registry.GeStateRecorder;
import io.jhpark.kopic.ge.room.service.RoomService;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class GeDrainService implements SmartLifecycle {

	private static final Duration FORCE_CLOSE_WAIT = Duration.ofSeconds(10);
	private static final Duration FORCE_CLOSE_POLL_INTERVAL = Duration.ofSeconds(1);

	private final GeRuntimeState runtimeState;
	private final DrainProperties drainProperties;
	private final GeStateRecorder stateRecorder;
	private final DefaultQuickRoomCandidateStore quickRoomCandidates;
	private final RoomService roomService;
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
			stateRecorder.heartbeat();
			stateRecorder.reportLoad();
			quickRoomCandidates.clearCurrentGeCandidates();
			int submittedCount = roomService.startDrain();
			log.info("ge drain room jobs submitted. geId={}, submittedCount={}, roomCount={}",
				runtimeState.geId(), submittedCount, runtimeState.roomCount());
			boolean drained = waitForRoomsToDrain(drainProperties.timeout(), drainProperties.pollInterval());
			if (!drained) {
				log.warn("ge drain timeout reached. geId={}, roomCount={}",
					runtimeState.geId(), runtimeState.roomCount());
				int forceCloseSubmittedCount = roomService.forceCloseAll("서버 종료 시간이 도달하여 로비로 이동합니다.");
				log.warn("ge drain force close submitted. geId={}, submittedCount={}",
					runtimeState.geId(), forceCloseSubmittedCount);
				waitForRoomsToDrain(FORCE_CLOSE_WAIT, FORCE_CLOSE_POLL_INTERVAL);
			}
			stateRecorder.stopRecording();
			running.set(false);
			log.info("ge drain lifecycle stop completed. phase={}, geId={}, roomCount={}",
				getPhase(), runtimeState.geId(), runtimeState.roomCount());
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

	private boolean waitForRoomsToDrain(Duration timeout, Duration pollInterval) {
		Instant deadline = Instant.now().plus(timeout);
		while (runtimeState.roomCount() > 0) {
			Instant now = Instant.now();
			if (!now.isBefore(deadline)) {
				return runtimeState.roomCount() == 0;
			}
			Duration remaining = Duration.between(now, deadline);
			log.info("ge drain waiting. geId={}, roomCount={}, remainingSeconds={}",
				runtimeState.geId(), runtimeState.roomCount(), remaining.toSeconds());
			if (!sleep(min(pollInterval, remaining))) {
				return false;
			}
		}
		return true;
	}

	private boolean sleep(Duration duration) {
		if (duration == null || duration.isZero() || duration.isNegative()) {
			return true;
		}
		try {
			Thread.sleep(duration.toMillis());
			return true;
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
			log.warn("ge drain wait interrupted. geId={}", runtimeState.geId());
			return false;
		}
	}

	private Duration min(Duration first, Duration second) {
		if (first.compareTo(second) <= 0) {
			return first;
		}
		return second;
	}
}
