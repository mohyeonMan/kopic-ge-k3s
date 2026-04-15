package io.jhpark.kopic.ge.room.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public final class RoomSession {

	private final Room room;
	private final Queue<Runnable> mailbox = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean draining = new AtomicBoolean(false);
	private volatile Instant lastTouchedAt = Instant.now();

	public RoomSession(Room room) {
		this.room = Objects.requireNonNull(room, "room");
	}

	public String roomId() {
		return room.getRoomId();
	}

	public Room room() {
		return room;
	}

	public Instant lastTouchedAt() {
		return lastTouchedAt;
	}

	public void touch(Instant touchedAt) {
		this.lastTouchedAt = Objects.requireNonNull(touchedAt, "touchedAt");
	}

	public void enqueue(Runnable job) {
		mailbox.add(Objects.requireNonNull(job, "job"));
	}

	public Runnable poll() {
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
}
