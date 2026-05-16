package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.error.ErrorCode;

public sealed interface RoomSubmitResult
	permits RoomSubmitResult.Accepted, RoomSubmitResult.Rejected {

	record Accepted() implements RoomSubmitResult {}

	record Rejected(
		ErrorCode errorCode,
		String message
	) implements RoomSubmitResult {}

	static RoomSubmitResult accepted() {
		return new Accepted();
	}

	static RoomSubmitResult rejected(
		ErrorCode errorCode,
		String message
	) {
		return new Rejected(errorCode, message);
	}
}
