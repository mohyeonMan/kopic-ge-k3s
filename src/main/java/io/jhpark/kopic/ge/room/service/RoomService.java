package io.jhpark.kopic.ge.room.service;

import java.util.Optional;

public interface RoomService {

	RoomSnapshot bootstrapRoom(String roomId, String ownerEngineId, String roomType, String hostSessionId, int capacity);

	Optional<RoomSnapshot> findRoom(String roomId);

	boolean canJoin(String roomId, String sessionId);

	void join(String roomId, String sessionId, String nickname, String wsNodeId, String ownerEngineId, String roomType, int capacity);

	void leave(String roomId, String sessionId);

	void snapshot(String roomId, String sessionId, String requestId);
	
	void submit(String roomId, RoomAction action);

	void closeRoom(String roomId);
}
