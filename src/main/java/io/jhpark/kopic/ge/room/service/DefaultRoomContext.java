package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class DefaultRoomContext implements RoomContext {

	private final RoomSession session;
	private final RoomService roomService;
	private final ScheduledExecutorService scheduler;

	DefaultRoomContext(
		RoomSession session,
		RoomService roomService,
		ScheduledExecutorService scheduler
	) {
		this.session = Objects.requireNonNull(session, "session");
		this.roomService = Objects.requireNonNull(roomService, "roomService");
		this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
	}

	@Override
	public Room room() {
		return session.getRoom();
	}

	@Override
	public String roomId() {
		return session.getRoom().getRoomId();
	}

	@Override
	public Instant now() {
		return Instant.now();
	}

	@Override
	public void closeRoom() {
		roomService.closeRoom(roomId());
	}

	@Override
	public void after(Duration delay, RoomAction action) {
		Objects.requireNonNull(action, "action");
		long delayMillis = delay == null ? 0L : Math.max(0L, delay.toMillis());
		scheduler.schedule(() -> {
			try {
				roomService.submit(roomId(), action);
			} catch (IllegalArgumentException ignored) {
				// Room was already closed before the delayed action fired.
			}
		}, delayMillis, TimeUnit.MILLISECONDS);
	}
}
