package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.Participant;
import io.jhpark.kopic.ge.room.dto.Room;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record RoomSnapshot(
	String roomId,
	String roomCode,
	String roomType,
	String roomState,
	String hostSessionId,
	String ownerEngineId,
	long version,
	int capacity,
	Map<String, Participant> participants
) {

	public static RoomSnapshot from(Room room) {
		return new RoomSnapshot(
			room.getRoomId(),
			room.getRoomCode(),
			room.getRoomType(),
			room.getRoomState(),
			room.getHostSessionId(),
			room.getOwnerEngineId(),
			room.getVersion(),
			room.getCapacity(),
			Collections.unmodifiableMap(new LinkedHashMap<>(room.getParticipants()))
		);
	}
}
