package io.jhpark.kopic.ge.room.service;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;


public interface RoomService {

	RoomSnapshot bootstrapRoom(String roomId, int roomType, String hostSessionId, int capacity);

	Optional<RoomSnapshot> findRoom(String roomId);

	boolean canJoin(String roomId, String sessionId);

	RoomSubmitResult privateJoin(String roomCode, String sessionId, String nickname, String wsNodeId);

	RoomSubmitResult quickJoin(String sessionId, String nickname, String wsNodeId);

	RoomSubmitResult leave(String roomId, String sessionId, String wsNodeId);

	RoomSubmitResult snapshot(String roomId, String sessionId, String requestId, String wsNodeId);

	RoomSubmitResult drawStroke(String roomId, String sessionId, JsonNode stroke);

	RoomSubmitResult guessChat(String roomId, String sessionId, String text);

	void closeRoom(String roomId);
}
