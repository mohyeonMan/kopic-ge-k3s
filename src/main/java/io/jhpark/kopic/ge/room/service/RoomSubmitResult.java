package io.jhpark.kopic.ge.room.service;

public sealed interface RoomSubmitResult
	permits RoomSubmitResult.Accepted, RoomSubmitResult.Rejected {

	record Accepted() implements RoomSubmitResult {}

	record Rejected(
		Reason reason,
		String message
	) implements RoomSubmitResult {}

	enum Reason {
		INVALID_REQUEST,
		ROOM_NOT_FOUND,
		MAILBOX_FULL,
		ACTOR_INACTIVE
	}

	static RoomSubmitResult accepted() {
		return new Accepted();
	}

	static RoomSubmitResult rejected(
		Reason reason,
		String message
	) {
		return new Rejected(reason, message);
	}
}
