package io.jhpark.kopic.ge.common.error;

public enum ErrorCode {
	MISSING_ENVELOPE(1901),
	UNSUPPORTED_EVENT(1902),
	INVALID_REQUEST(1903),
	ROOM_NOT_FOUND(1910),
	ROOM_FULL(1911),
	FORBIDDEN(1920),
	CONFLICT(1930),
	MAILBOX_FULL(1940),
	ACTOR_INACTIVE(1941),
	UNKNOWN(1999);

	private final int eventCode;

	ErrorCode(int eventCode) {
		this.eventCode = eventCode;
	}

	public int eventCode() {
		return eventCode;
	}

	public String reason() {
		return name();
	}
}
