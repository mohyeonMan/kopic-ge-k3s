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

	public static final int QUICK_ROOM_CAPACITY = 5;
	public static final int PRIVATE_ROOM_CAPACITY = 50;
	public static final String ROOM_ID_PREFIX = "rid_";
	public static final int QUICK_ROOM_TYPE = 0;
	public static final int PRIVATE_ROOM_TYPE = 1;
	private static final int ROOM_ID_SUFFIX_LENGTH = 8;
	private static final int ROOM_CODE_SUFFIX_LENGTH = 8;

	private final String roomId;
	private final String roomCode;
	private final int roomType;
	private final int capacity;
	private volatile String hostSessionId;
	private volatile Setting setting;
	private volatile Game game;
	private volatile Instant autoRestartAt;
	private final Map<String, Participant> participants = new ConcurrentHashMap<>();
	private final Instant createdAt;
	private final List<JsonNode> currentCanvas = new ArrayList<>();
	private final List<List<JsonNode>> canvasRedoStack = new ArrayList<>();

	public Room(int roomType, String hostSessionId) {
		this.roomId = newRoomId();
		this.roomCode = roomType == PRIVATE_ROOM_TYPE ? newRoomCode() : null;
		this.roomType = roomType;
		this.capacity = roomType == PRIVATE_ROOM_TYPE ? Room.PRIVATE_ROOM_CAPACITY : Room.QUICK_ROOM_CAPACITY;
		this.createdAt = Instant.now();
		this.hostSessionId = hostSessionId;
		this.setting = roomType == PRIVATE_ROOM_TYPE ? Setting.privateDefaultValue() : Setting.publicDefaultValue();
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

	public Game startGame(List<WordEntry> customWordPool) {
		this.autoRestartAt = null;
		this.game = Game.start(this.setting.copy(), customWordPool);

		return this.game;
	}

	public void endGame() {
		this.game = null;
	}

	public void setAutoRestartAt(Instant autoRestartAt) {
		this.autoRestartAt = Objects.requireNonNull(autoRestartAt, "autoRestartAt");
	}

	public void clearAutoRestartAt() {
		this.autoRestartAt = null;
	}

	public void appendCanvasEvent(JsonNode stroke) {
		canvasRedoStack.clear();
		currentCanvas.add(stroke);
	}

	public boolean undoCanvas(String cid) {
		List<JsonNode> removedStrokes = new ArrayList<>();
		boolean removingTargetCid = false;

		for (int index = currentCanvas.size() - 1; index >= 0; index--) {
			JsonNode canvasStroke = currentCanvas.get(index);
			boolean matchesTargetCid = hasCanvasCid(canvasStroke, cid);

			if (!matchesTargetCid) {
				if (removingTargetCid) {
					break;
				}
				continue;
			}

			removingTargetCid = true;
			removedStrokes.add(0, currentCanvas.remove(index));
		}

		if (removedStrokes.isEmpty()) {
			return false;
		}

		canvasRedoStack.add(removedStrokes);
		return true;
	}

	public boolean redoCanvas(String cid) {
		if (canvasRedoStack.isEmpty()) {
			return false;
		}

		List<JsonNode> redoGroup = canvasRedoStack.get(canvasRedoStack.size() - 1);
		if (redoGroup.isEmpty() || !hasCanvasCid(redoGroup.get(0), cid)) {
			return false;
		}

		canvasRedoStack.remove(canvasRedoStack.size() - 1);
		currentCanvas.addAll(redoGroup);
		return true;
	}

	public void clearCanvasHistory() {
		currentCanvas.clear();
		canvasRedoStack.clear();
	}

	private boolean hasCanvasCid(JsonNode stroke, String cid) {
		return stroke != null
			&& stroke.isArray()
			&& stroke.size() > 3
			&& stroke.get(3).isTextual()
			&& cid.equals(stroke.get(3).asText());
	}
}
