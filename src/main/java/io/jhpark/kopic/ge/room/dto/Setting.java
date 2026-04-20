package io.jhpark.kopic.ge.room.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

public record Setting(
	int roundCount,
	int drawSec,
	int wordChoiceSec,
	int wordChoiceCount,
	int hintRevealSec,
	int hintLetterCount,
	DrawerOrderMode drawerOrderMode,
	EndMode endMode
) {

	private static final int PAYLOAD_SIZE = 8;

	public static Setting defaultValue() {
		return new Setting(
			3,
			40,
			10,
			3,
			10,
			1,
			DrawerOrderMode.JOIN_ORDER,
			EndMode.FIRST_CORRECT
		);
	}

	public static Setting fromPayload(JsonNode payload) {
		if (payload == null || !payload.isArray()) {
			throw new IllegalArgumentException("setting payload must be array");
		}
		if (payload.size() != PAYLOAD_SIZE) {
			throw new IllegalArgumentException("setting payload size must be " + PAYLOAD_SIZE);
		}
		return new Setting(
			readInt(payload, 0, "roundCount"),
			readInt(payload, 1, "drawSec"),
			readInt(payload, 2, "wordChoiceSec"),
			readInt(payload, 3, "wordChoiceCount"),
			readInt(payload, 4, "hintRevealSec"),
			readInt(payload, 5, "hintLetterCount"),
			DrawerOrderMode.fromCode(readInt(payload, 6, "drawerOrderMode")),
			EndMode.fromCode(readInt(payload, 7, "endMode"))
		);
	}

	private static int readInt(JsonNode payload, int index, String fieldName) {
		JsonNode value = payload.get(index);
		if (value == null || !value.canConvertToInt()) {
			throw new IllegalArgumentException("setting field must be int: " + fieldName);
		}
		return value.asInt();
	}

	public Setting copy() {
		return new Setting(
			roundCount,
			drawSec,
			wordChoiceSec,
			wordChoiceCount,
			hintRevealSec,
			hintLetterCount,
			drawerOrderMode,
			endMode
		);
	}

	public List<Integer> toPayload() {
		return List.of(
			roundCount,
			drawSec,
			wordChoiceSec,
			wordChoiceCount,
			hintRevealSec,
			hintLetterCount,
			drawerOrderMode.code(),
			endMode.code()
		);
	}
}
