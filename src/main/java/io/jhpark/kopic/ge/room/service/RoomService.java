package io.jhpark.kopic.ge.room.service;

import java.util.Optional;

public interface RoomService {

	RoomSnapshot bootstrapRoom(String roomId, String ownerEngineId, String roomType, String hostUserId, int capacity);

	Optional<RoomSnapshot> findRoom(String roomId);

	boolean canJoin(String roomId, String userId);

	void join(String roomId, String userId, String nickname, String ownerEngineId, String roomType, int capacity);

	void leave(String roomId, String userId);

	void snapshot(String roomId, String userId, String requestId);

	void submit(String roomId, RoomAction action);

	void closeRoom(String roomId);
}
