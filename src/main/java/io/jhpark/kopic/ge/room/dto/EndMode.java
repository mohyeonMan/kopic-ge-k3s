package io.jhpark.kopic.ge.room.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EndMode {

	FIRST_CORRECT(0),
	TIME_OR_ALL_CORRECT(1);

	private final int code;

	EndMode(int code) {
		this.code = code;
	}

	@JsonValue
	public int code() {
		return code;
	}

	public static EndMode fromCode(int code) {
		for (EndMode mode : values()) {
			if (mode.code == code) {
				return mode;
			}
		}
		throw new IllegalArgumentException("invalid endMode code: " + code);
	}
}
