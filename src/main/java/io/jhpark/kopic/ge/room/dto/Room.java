package io.jhpark.kopic.ge.room.dto;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;

@Getter
public final class Room {

	private final String roomId;
	private final int roomType;
	private final String hostSessionId;
	private final Map<String, Participant> participants = new ConcurrentHashMap<>();
	private final Instant createdAt;
	private final List<JsonNode> currentCanvas = new ArrayList<>();

	public Room(String hostSessionId) {
		this.roomId = "rid_"+UUID.randomUUID().toString().substring(0, 8);
		this.roomType = 1;
		this.createdAt = Instant.now();
		this.hostSessionId = hostSessionId;
	}

	public Room(){
		this.roomId = "rid_"+UUID.randomUUID().toString().substring(0, 8);
		this.roomType = 0;
		this.createdAt = Instant.now();
		this.hostSessionId = null;
	}
}
