package io.jhpark.kopic.ge.room.service;

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

	public DefaultRoomRunner(
		RoomSessionStore sessionStore,
		@Qualifier("roomRunnerExecutor") Executor executor,
		@Qualifier("roomRunnerScheduler") ScheduledExecutorService scheduler
	) {
		this.sessionStore = sessionStore;
		this.executor = executor;
		this.scheduler = scheduler;
	}

	@Override
	public RoomSubmitResult submit(String roomId, RoomJob job) {
		if (isBlank(roomId)) {
			log.warn("roomId is blank. reject room job.");
			return RoomSubmitResult.rejected(
				RoomSubmitResult.Reason.INVALID_REQUEST,
				"roomId is required"
			);
		}
		if (job == null) {
			log.warn("job is null. reject room job. roomId={}", roomId);
			return RoomSubmitResult.rejected(
				RoomSubmitResult.Reason.INVALID_REQUEST,
				"job is required"
			);
		}

		RoomSession session = sessionStore.find(roomId).orElse(null);
		if (session == null) {
			return RoomSubmitResult.rejected(
				RoomSubmitResult.Reason.ROOM_NOT_FOUND,
				"room not found: " + roomId
			);
		}

		if (!session.enqueue(job)) {
			RoomSubmitResult.Reason reason = session.isActive()
				? RoomSubmitResult.Reason.MAILBOX_FULL
				: RoomSubmitResult.Reason.ACTOR_INACTIVE;
			return RoomSubmitResult.rejected(
				reason,
				"room mailbox is full or inactive. roomId=" + roomId
			);
		}
		schedule(session);
		return RoomSubmitResult.accepted();
	}

	private void execute(RoomSession session, RoomJob job) {
		try {
			RoomJob.FollowUpResult result = job.action().apply(session.getRoom());
			session.touch(Instant.now());
			applyResult(session, result);
		} catch (RuntimeException runtimeException) {
			log.error("room job failed. roomId={}",
				session.getRoom().getRoomId(),
				runtimeException);
		}
	}

	private void applyResult(RoomSession session, RoomJob.FollowUpResult result) {
		if (result == null) {
			return;
		}

		if (!isBlank(result.cancelTimerKey())) {
			session.cancelTimer(result.cancelTimerKey());
		}
		if (result.closeIfEmpty() && session.getRoom().getParticipants().isEmpty()) {
			closeActor(session);
			return;
		}
		if (result.followUp() != null) {
			applyFollowUp(session, result.followUp());
		}
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
				rejected.reason(),
				rejected.message());
		}
	}

	private void scheduleFollowUp(RoomSession session, RoomJob.FollowUp followUp) {
		String roomId = session.getRoom().getRoomId();
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

	private void closeActor(RoomSession session) {
		String roomId = session.getRoom().getRoomId();
		boolean removed = sessionStore.remove(roomId, session);
		session.close();
		if (removed) {
			log.info("room actor closed. roomId={}", roomId);
		}
	}
}
