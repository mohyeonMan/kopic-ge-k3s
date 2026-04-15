package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.RoomSession;
import io.jhpark.kopic.ge.room.registry.RoomSessionStore;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public final class DefaultRoomRunner implements RoomRunner {

	private final RoomSessionStore sessionStore;
	private final Executor executor;
	private final ScheduledExecutorService scheduler;
	private final RoomService roomService;

	public DefaultRoomRunner(
		RoomSessionStore sessionStore,
		@Qualifier("roomRunnerExecutor") Executor executor,
		@Qualifier("roomRunnerScheduler") ScheduledExecutorService scheduler,
		RoomService roomService
	) {
		this.sessionStore = sessionStore;
		this.executor = executor;
		this.scheduler = scheduler;
		this.roomService = roomService;
	}

	@Override
	public void submit(String roomId, RoomAction action) {
		Objects.requireNonNull(roomId, "roomId");
		Objects.requireNonNull(action, "action");

		RoomSession session = sessionStore.find(roomId)
			.orElseThrow(() -> new IllegalArgumentException("room not found: " + roomId));

		session.enqueue(() -> execute(session, action));
		schedule(session);
	}

	private void execute(RoomSession session, RoomAction action) {
		try {
			action.run(new DefaultRoomContext(session, roomService, scheduler));
			session.touch(Instant.now());
		} catch (Throwable throwable) {
			log.error("room action failed. roomId={}", session.roomId(), throwable);
		}
	}

	private void schedule(RoomSession session) {
		if (session.markDraining()) {
			executor.execute(() -> drain(session));
		}
	}

	private void drain(RoomSession session) {
		try {
			Runnable job;
			while ((job = session.poll()) != null) {
				job.run();
			}
		} finally {
			session.finishDrain();
			if (session.hasPendingJobs()) {
				schedule(session);
			}
		}
	}
}
