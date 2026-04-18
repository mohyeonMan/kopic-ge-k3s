package io.jhpark.kopic.ge.room.dto;

public record Participant(
	String wsNodeId,
	String sessionId,
	String nickname,
	String joinedAt
) {
}
