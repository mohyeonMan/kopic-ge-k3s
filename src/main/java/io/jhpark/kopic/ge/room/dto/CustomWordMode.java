package io.jhpark.kopic.ge.room.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum CustomWordMode {

	CUSTOM_ONLY(0),
	BASE_PLUS_CUSTOM(1);

	private final int code;

	CustomWordMode(int code) {
		this.code = code;
	}

	@JsonValue
	public int code() {
		return code;
	}

	public static CustomWordMode fromCode(int code) {
		for (CustomWordMode mode : values()) {
			if (mode.code == code) {
				return mode;
			}
		}
		throw new IllegalArgumentException("invalid customWordMode code: " + code);
	}
}
