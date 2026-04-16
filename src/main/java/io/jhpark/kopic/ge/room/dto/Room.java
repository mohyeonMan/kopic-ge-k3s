package io.jhpark.kopic.ge.room.dto;

import java.time.Instant;
import java.util.Map;
import lombok.Getter;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Getter
public final class Room {

	private final String roomId;
	private final Map<String, Participant> participants = new ConcurrentHashMap<>();
	private final Instant createdAt;

	public Room(String roomId) {
		this.roomId = Objects.requireNonNull(roomId, "roomId");
		this.createdAt = Instant.now();
	}
}
