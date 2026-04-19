package io.jhpark.kopic.ge.room.dto;

import com.fasterxml.jackson.annotation.JsonValue;

public enum DrawerOrderMode {

	JOIN_ORDER(0),
	RANDOM(1);

	private final int code;

	DrawerOrderMode(int code) {
		this.code = code;
	}

	@JsonValue
	public int code() {
		return code;
	}

	public static DrawerOrderMode fromCode(int code) {
		for (DrawerOrderMode mode : values()) {
			if (mode.code == code) {
				return mode;
			}
		}
		throw new IllegalArgumentException("invalid drawerOrderMode code: " + code);
	}
}
