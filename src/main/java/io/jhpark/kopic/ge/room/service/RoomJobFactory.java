package io.jhpark.kopic.ge.room.service;

public interface RoomJobFactory {

	RoomJob join(String sessionId, String nickname, String wsNodeId);

	RoomJob leave(String sessionId);

	RoomJob snapshot(String sessionId, String requestId);

	RoomJob closeIfEmpty();
}
