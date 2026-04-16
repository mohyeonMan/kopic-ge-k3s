package io.jhpark.kopic.ge.room.service;

import java.util.Optional;

public interface RoomService {

	RoomSnapshot bootstrapRoom(String roomId, String ownerEngineId, String roomType, String hostSessionId, int capacity);

	Optional<RoomSnapshot> findRoom(String roomId);

	boolean canJoin(String roomId, String sessionId);

	RoomSubmitResult join(String roomId, String sessionId, String nickname, String wsNodeId);

	RoomSubmitResult leave(String roomId, String sessionId, String wsNodeId);

	RoomSubmitResult snapshot(String roomId, String sessionId, String requestId, String wsNodeId);

	void closeRoom(String roomId);
}
