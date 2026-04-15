package io.jhpark.kopic.ge.room.dto;

import java.time.Instant;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class Room {

	private final String roomId;
	private String roomCode;
	private String roomType;
	private final Map<String, Participant> participants;
	private String roomState;
	private final Instant createdAt;
	private String hostSessionId;
	private final String ownerEngineId;
	private long version;
	private final int capacity;

	public void increaseVersion() {
		version += 1;
	}
}
