package io.jhpark.kopic.ge.room.service;

public record RoomJobMeta(
	String sessionId,
	String wsNodeId,
	String requestId
) {

	private static final RoomJobMeta NONE = new RoomJobMeta(null, null, null);

	public static RoomJobMeta none() {
		return NONE;
	}
}
