package io.jhpark.kopic.ge.common.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class GeRuntimeState {

	private final String geId;
	private final AtomicReference<GeStatus> status = new AtomicReference<>(GeStatus.ACTIVE);
	private final AtomicInteger roomCount = new AtomicInteger();
	private final AtomicInteger participantCount = new AtomicInteger();

	public GeRuntimeState(@Value("${kopic.node-id:ge-local}") String geId) {
		this.geId = normalizeGeId(geId);
	}

	public String geId() {
		return geId;
	}

	public GeStatus status() {
		return status.get();
	}

	public String statusValue() {
		return status().name();
	}

	public boolean isActive() {
		return status() == GeStatus.ACTIVE;
	}

	public boolean isDraining() {
		return status() == GeStatus.DRAIN;
	}

	public boolean enterDrain() {
		return status.compareAndSet(GeStatus.ACTIVE, GeStatus.DRAIN);
	}

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
	}

	public int roomCount() {
		return roomCount.get();
	}

	public int participantCount() {
		return participantCount.get();
	}

	public double loadScore(double roomWeight) {
		return participantCount() + roomCount() * roomWeight;
	}

	private void decrement(AtomicInteger counter) {
		counter.updateAndGet(value -> Math.max(0, value - 1));
	}

	private String normalizeGeId(String value) {
		return value == null || value.isBlank() ? "ge-local" : value.trim();
	}
}
