package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.RoomSession;
import io.jhpark.kopic.ge.room.registry.RoomSessionStore;
import java.time.Instant;
import java.util.Objects;
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
	public RoomSubmitResult submit(RoomJob job, RoomJobMeta meta) {
		RoomJobMeta safeMeta = meta == null ? RoomJobMeta.none() : meta;
		if (job == null) {
			return RoomSubmitResult.rejected(
				RoomSubmitResult.Reason.INVALID_REQUEST,
				"job is required",
				safeMeta.sessionId(),
				safeMeta.wsNodeId(),
				safeMeta.requestId()
			);
		}

		String roomId = job.roomId();
		RoomSession session = sessionStore.find(roomId).orElse(null);
		if (session == null) {
			return RoomSubmitResult.rejected(
				RoomSubmitResult.Reason.ROOM_NOT_FOUND,
				"room not found: " + roomId,
				safeMeta.sessionId(),
				safeMeta.wsNodeId(),
				safeMeta.requestId()
			);
		}

		if (!session.enqueue(job)) {
			RoomSubmitResult.Reason reason = session.isActive()
				? RoomSubmitResult.Reason.MAILBOX_FULL
				: RoomSubmitResult.Reason.ACTOR_INACTIVE;
			return RoomSubmitResult.rejected(
				reason,
				"room mailbox is full or inactive. roomId=" + roomId,
				safeMeta.sessionId(),
				safeMeta.wsNodeId(),
				safeMeta.requestId()
			);
		}
		schedule(session);
		return RoomSubmitResult.accepted();
	}

	private void execute(RoomSession session, RoomJob job) {
		try {
			RoomJob.Result result = job.action().apply(session);
			session.touch(Instant.now());
			applyResult(session, result);
		} catch (RuntimeException runtimeException) {
			log.error("room job failed. roomId={}",
				session.getRoom().getRoomId(),
				runtimeException);
		}
	}

	private void applyResult(RoomSession session, RoomJob.Result result) {
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
		if (result.nextJob() != null) {
			submitFollowUp(session, result.nextJob());
		}
		if (result.timer() != null) {
			scheduleFollowUp(session, result.timer());
		}
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

	private void submitFollowUp(RoomSession session, RoomJob nextJob) {
		if (nextJob == null) {
			return;
		}

		if (isSameRoom(session, nextJob)) {
			if (session.enqueue(nextJob)) {
				schedule(session);
				return;
			}
			log.warn("follow-up room job dropped. roomId={}, reason=MAILBOX_FULL_OR_INACTIVE",
				nextJob.roomId());
			return;
		}

		if (submit(nextJob, RoomJobMeta.none()) instanceof RoomSubmitResult.Rejected rejected) {
			log.warn("follow-up room job rejected. roomId={}, reason={}, message={}",
				nextJob.roomId(),
				rejected.reason(),
				rejected.message());
		}
	}

	private void scheduleFollowUp(RoomSession session, RoomJob.Timer scheduledInstruction) {
		session.registerTimer(
			scheduledInstruction.timerKey(),
			scheduler.schedule(
				() -> submitFollowUp(session, scheduledInstruction.nextJob()),
				scheduledInstruction.delay().toMillis(),
				TimeUnit.MILLISECONDS
			)
		);
	}

	private boolean isSameRoom(RoomSession session, RoomJob nextJob) {
		return Objects.equals(session.getRoom().getRoomId(), nextJob.roomId());
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
