package io.jhpark.kopic.ge.room.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;

@Getter
public final class Room {

	public static final int DEFAULT_ROOM_CAPACITY = 2;
	public static final String ROOM_ID_PREFIX = "rid_";
	public static final int QUICK_ROOM_TYPE = 0;
	public static final int PRIVATE_ROOM_TYPE = 1;
	private static final int ROOM_ID_SUFFIX_LENGTH = 8;

	private final String roomId;
	private final int roomType;
	private final int capacity;
	private final String hostSessionId;
	private final Map<String, Participant> participants = new ConcurrentHashMap<>();
	private final Instant createdAt;
	private final List<JsonNode> currentCanvas = new ArrayList<>();

	public Room(int roomType, String hostSessionId) {
		this.roomId = newRoomId();
		this.roomType = roomType;
		this.capacity = Room.DEFAULT_ROOM_CAPACITY;
		this.createdAt = Instant.now();
		this.hostSessionId = hostSessionId;
	}

	public static String newRoomId() {
		return ROOM_ID_PREFIX + UUID.randomUUID().toString().substring(0, ROOM_ID_SUFFIX_LENGTH);
	}
}
