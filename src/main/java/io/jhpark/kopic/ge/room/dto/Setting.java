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
	EndMode endMode,
	CustomWordMode customWordMode,
	String customWordsRaw
) {

	private static final int PAYLOAD_SIZE = 10;

	public static Setting publicDefaultValue() {
		return new Setting(
			3,
			60,
			10,
			3,
			50,
			1,
			DrawerOrderMode.JOIN_ORDER,
			EndMode.TIME_OR_ALL_CORRECT,
			CustomWordMode.BASE_PLUS_CUSTOM,
			""
		);
	}

	public static Setting privateDefaultValue() {
		return new Setting(
			5,
			60,
			10,
			3,
			50,
			1,
			DrawerOrderMode.JOIN_ORDER,
			EndMode.FIRST_CORRECT,
			CustomWordMode.BASE_PLUS_CUSTOM,
			""
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
			EndMode.fromCode(readInt(payload, 7, "endMode")),
			CustomWordMode.fromCode(readInt(payload, 8, "customWordMode")),
			readText(payload, 9, "customWordsRaw")
		);
	}

	private static int readInt(JsonNode payload, int index, String fieldName) {
		JsonNode value = payload.get(index);
		if (value == null || !value.canConvertToInt()) {
			throw new IllegalArgumentException("setting field must be int: " + fieldName);
		}
		return value.asInt();
	}

	private static String readText(JsonNode payload, int index, String fieldName) {
		JsonNode value = payload.get(index);
		if (value == null || value.isNull()) {
			return "";
		}
		if (!value.isTextual()) {
			throw new IllegalArgumentException("setting field must be string: " + fieldName);
		}
		String raw = value.asText();
		return raw == null ? "" : raw;
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
			endMode,
			customWordMode,
			customWordsRaw
		);
	}

	public List<Object> toPayload() {
		return List.of(
			roundCount,
			drawSec,
			wordChoiceSec,
			wordChoiceCount,
			hintRevealSec,
			hintLetterCount,
			drawerOrderMode.code(),
			endMode.code(),
			customWordMode.code(),
			customWordsRaw == null ? "" : customWordsRaw
		);
	}
}
