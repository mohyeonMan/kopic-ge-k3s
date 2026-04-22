package io.jhpark.kopic.ge.room.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.Getter;

@Getter
public final class Room {

	public static final int DEFAULT_ROOM_CAPACITY = 8;
	public static final String ROOM_ID_PREFIX = "rid_";
	public static final int QUICK_ROOM_TYPE = 0;
	public static final int PRIVATE_ROOM_TYPE = 1;
	private static final int ROOM_ID_SUFFIX_LENGTH = 8;
	private static final int ROOM_CODE_SUFFIX_LENGTH = 6;

	private final String roomId;
	private final String roomCode;
	private final int roomType;
	private final int capacity;
	private volatile String hostSessionId;
	private volatile Setting setting;
	private volatile Game game;
	private final Map<String, Participant> participants = new ConcurrentHashMap<>();
	private final Instant createdAt;
	private final List<JsonNode> currentCanvas = new ArrayList<>();

	public Room(int roomType, String hostSessionId) {
		this.roomId = newRoomId();
		this.roomCode = roomType == PRIVATE_ROOM_TYPE ? newRoomCode() : null;
		this.roomType = roomType;
		this.capacity = Room.DEFAULT_ROOM_CAPACITY;
		this.createdAt = Instant.now();
		this.hostSessionId = hostSessionId;
		this.setting = Setting.defaultValue();
	}

	public static String newRoomId() {
		return ROOM_ID_PREFIX + UUID.randomUUID().toString().substring(0, ROOM_ID_SUFFIX_LENGTH);
	}

	public static String newRoomCode() {
		return UUID.randomUUID().toString().replace("-", "").substring(0, ROOM_CODE_SUFFIX_LENGTH).toUpperCase();
	}

	public void transferHost(String nextHostSessionId) {
		this.hostSessionId = nextHostSessionId;
	}

	public void updateSetting(Setting setting) {
		this.setting = Objects.requireNonNull(setting, "setting");
	}

	public Game startGame() {
		this.game = Game.start(this.setting.copy());

		return this.game;
	}

	public void endGame() {
		this.game = null;
	}
}
