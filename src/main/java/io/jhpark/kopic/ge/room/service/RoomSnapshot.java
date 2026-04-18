package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.Participant;
import io.jhpark.kopic.ge.room.dto.Room;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

public record RoomSnapshot(
	String roomId,
	int participantCount,
	Map<String, Participant> participants,
	List<JsonNode> currentCanvas
) {

	public static RoomSnapshot from(Room room) {
		Map<String, Participant> copiedParticipants =
			Collections.unmodifiableMap(new LinkedHashMap<>(room.getParticipants()));
		List<JsonNode> copiedCanvas = List.copyOf(room.getCurrentCanvas());
		return new RoomSnapshot(
			room.getRoomId(),
			copiedParticipants.size(),
			copiedParticipants,
			copiedCanvas
		);
	}
}
