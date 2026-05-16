package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.error.ErrorCode;
import io.jhpark.kopic.ge.common.metrics.GeMetrics;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import io.jhpark.kopic.ge.room.registry.RoomSessionStore;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public final class DefaultRoomRunner implements RoomRunner {

	private final RoomSessionStore sessionStore;
	private final Executor executor;
	private final ScheduledExecutorService scheduler;
	private final GeMetrics geMetrics;

	public DefaultRoomRunner(
		RoomSessionStore sessionStore,
		@Qualifier("roomRunnerExecutor") Executor executor,
		@Qualifier("roomRunnerScheduler") ScheduledExecutorService scheduler,
		GeMetrics geMetrics
	) {
		this.sessionStore = sessionStore;
		this.executor = executor;
		this.scheduler = scheduler;
		this.geMetrics = geMetrics;
	}

	@Override
	public RoomSubmitResult submit(String roomId, RoomJob job) {
		if (isBlank(roomId)) {
			log.warn("roomId is blank. reject room job.");
			return recordSubmitResult(RoomSubmitResult.rejected(
				ErrorCode.INVALID_REQUEST,
				"roomId is required"
			));
		}
		if (job == null) {
			log.warn("job is null. reject room job. roomId={}", roomId);
			return recordSubmitResult(RoomSubmitResult.rejected(
				ErrorCode.INVALID_REQUEST,
				"job is required"
			));
		}

		RoomSession session = sessionStore.find(roomId).orElse(null);
		if (session == null) {
			log.warn("room job rejected because room not found. roomId={}", roomId);
			return recordSubmitResult(RoomSubmitResult.rejected(
				ErrorCode.ROOM_NOT_FOUND,
				"room not found: " + roomId
			));
		}

		if (!session.enqueue(job)) {
			ErrorCode errorCode = session.isActive()
				? ErrorCode.MAILBOX_FULL
				: ErrorCode.ACTOR_INACTIVE;
			log.warn("room job rejected because enqueue failed. roomId={}, errorCode={}", roomId, errorCode);
			return recordSubmitResult(RoomSubmitResult.rejected(
				errorCode,
				"room mailbox is full or inactive. roomId=" + roomId
			));
		}
		schedule(session);
		return recordSubmitResult(RoomSubmitResult.accepted());
	}

	private void execute(RoomSession session, RoomJob job) {
		long startedAtNanos = System.nanoTime();
		try {
			RoomJob.FollowUpResult result = job.action().apply(session.getRoom());
			session.touch(Instant.now());
			applyResult(session, result);
		} catch (RuntimeException runtimeException) {
			log.error("room job failed. roomId={}",
				session.getRoom().getRoomId(),
				runtimeException);
		} finally {
			geMetrics.recordDuration(
				"kopic_ge_room_job_duration_seconds",
				System.nanoTime() - startedAtNanos
			);
		}
	}

	private void applyResult(RoomSession session, RoomJob.FollowUpResult result) {
		if (result == null) {
			return;
		}
		if (!isBlank(result.cancelTimerKey())) {
			geMetrics.increment(
				"kopic_ge_timer_cancelled_total",
				"timer_key",
				result.cancelTimerKey()
			);
			session.cancelTimer(result.cancelTimerKey());
		}
		if (applyFollowUpAction(session, result.followUpAction())) {
			return;
		}
		if (result.followUps() != null && !result.followUps().isEmpty()) {
			for (RoomJob.FollowUp followUp : result.followUps()) {
				applyFollowUp(session, followUp);
			}
		}
	}

	private boolean applyFollowUpAction(RoomSession session, RoomJob.FollowUpAction action) {
		if (action == null || action == RoomJob.FollowUpAction.NONE) {
			return false;
		}
		String roomId = session.getRoom().getRoomId();
		switch (action) {
			case REQUEST_CLOSE_IF_EMPTY -> {
				closeActor(session);
				return true;
			}
			case ADD_QUICK_JOIN_CANDIDATE -> {
				sessionStore.addQuickJoinCandidate(roomId);
				log.debug("quick join candidate added. roomId={}", roomId);
			}
			case REMOVE_QUICK_JOIN_CANDIDATE -> {
				sessionStore.removeQuickJoinCandidate(roomId);
				log.debug("quick join candidate removed. roomId={}", roomId);
			}
			case NONE -> {
				return false;
			}
		}
		return false;
	}

	private void applyFollowUp(RoomSession session, RoomJob.FollowUp followUp) {
		if (followUp.delayed()) {
			scheduleFollowUp(session, followUp);
			return;
		}
		submitFollowUp(session.getRoom().getRoomId(), followUp.nextJob());
	}

	private void schedule(RoomSession session) {
		if (session.markDraining()) {
			executor.execute(() -> drain(session));
		}
	}

	private void drain(RoomSession session) {
		try {
			RoomJob job;
			while ((job = session.poll()) != null) {
				execute(session, job);
			}
		} finally {
			session.finishDrain();
			if (session.hasPendingJobs()) {
				schedule(session);
			}
		}
	}

	private void submitFollowUp(String roomId, RoomJob nextJob) {
		if (nextJob == null) {
			return;
		}

		if (submit(roomId, nextJob) instanceof RoomSubmitResult.Rejected rejected) {
			log.warn("follow-up room job rejected. roomId={}, reason={}, message={}",
				roomId,
				rejected.errorCode() != null ? rejected.errorCode().reason() : null,
				rejected.message());
		}
	}

	private void scheduleFollowUp(RoomSession session, RoomJob.FollowUp followUp) {
		String roomId = session.getRoom().getRoomId();
		log.debug("room follow-up scheduled. roomId={}, timerKey={}, delayMs={}",
			roomId, followUp.timerKey(), followUp.delay().toMillis());
		geMetrics.increment(
			"kopic_ge_timer_scheduled_total",
			"timer_key",
			followUp.timerKey()
		);
		session.registerTimer(
			followUp.timerKey(),
			scheduler.schedule(
				() -> submitFollowUp(roomId, followUp.nextJob()),
				followUp.delay().toMillis(),
				TimeUnit.MILLISECONDS
			)
		);
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private RoomSubmitResult recordSubmitResult(RoomSubmitResult result) {
		String resultLabel = "rejected";
		String reasonLabel = "unknown";
		if (result instanceof RoomSubmitResult.Accepted) {
			resultLabel = "accepted";
			reasonLabel = "none";
		} else if (result instanceof RoomSubmitResult.Rejected rejected) {
			reasonLabel = rejected.errorCode() != null ? rejected.errorCode().reason() : "unknown";
		}
		geMetrics.increment(
			"kopic_ge_room_submit_total",
			"result",
			resultLabel,
			"reason",
			reasonLabel
		);
		return result;
	}

	private void closeActor(RoomSession session) {
		String roomId = session.getRoom().getRoomId();
		int participantCount = session.getRoom().getParticipants().size();
		log.debug("closing room actor requested. roomId={}, participantCount={}", roomId, participantCount);
		boolean removed = sessionStore.remove(roomId, session);
		session.close();
		if (removed) {
			log.debug("room actor closed. roomId={}", roomId);
		} else {
			log.warn("room actor close skipped because session mapping changed. roomId={}", roomId);
		}
	}
}
