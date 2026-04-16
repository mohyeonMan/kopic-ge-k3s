package io.jhpark.kopic.ge.room.dto;

import io.jhpark.kopic.ge.room.service.RoomJob;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.Getter;

@Getter
public final class RoomSession {

	private static final int DEFAULT_MAILBOX_CAPACITY = 1024;

	private final Room room;
	private final BlockingQueue<RoomJob> mailbox = new ArrayBlockingQueue<>(DEFAULT_MAILBOX_CAPACITY);
	private final AtomicBoolean draining = new AtomicBoolean(false);
	private final Map<String, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
	private volatile Instant lastTouchedAt = Instant.now();
	private volatile boolean active = true;

	public RoomSession(Room room) {
		this.room = Objects.requireNonNull(room, "room");
	}

	public void touch(Instant touchedAt) {
		this.lastTouchedAt = Objects.requireNonNull(touchedAt, "touchedAt");
	}

	public boolean enqueue(RoomJob event) {
		if (!active) {
			return false;
		}
		return mailbox.offer(Objects.requireNonNull(event, "event"));
	}

	public RoomJob poll() {
		return mailbox.poll();
	}

	public boolean hasPendingJobs() {
		return !mailbox.isEmpty();
	}

	public boolean markDraining() {
		return draining.compareAndSet(false, true);
	}

	public void finishDrain() {
		draining.set(false);
	}

	public void registerTimer(String timerKey, ScheduledFuture<?> timerFuture) {
		Objects.requireNonNull(timerKey, "timerKey");
		Objects.requireNonNull(timerFuture, "timerFuture");
		ScheduledFuture<?> existing = timers.put(timerKey, timerFuture);
		if (existing != null) {
			existing.cancel(false);
		}
	}

	public void cancelTimer(String timerKey) {
		if (timerKey == null || timerKey.isBlank()) {
			return;
		}
		ScheduledFuture<?> existing = timers.remove(timerKey);
		if (existing != null) {
			existing.cancel(false);
		}
	}

	public void close() {
		active = false;
		for (ScheduledFuture<?> future : timers.values()) {
			future.cancel(false);
		}
		timers.clear();
		mailbox.clear();
	}
}
