package io.jhpark.kopic.ge.room.service;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

import io.jhpark.kopic.ge.room.dto.Room;


public interface RoomService {

	Room bootstrapRoom(int roomType, String hostSessionId);

	Optional<RoomSnapshot> findRoom(String roomId);

	RoomSubmitResult createPrivateRoom(String sessionId, String nickname, String wsNodeId);

	RoomSubmitResult privateJoin(String roomCode, String sessionId, String nickname, String wsNodeId);

	RoomSubmitResult quickJoin(String sessionId, String nickname, String wsNodeId);

	RoomSubmitResult leave(String roomId, String sessionId, String wsNodeId);

	RoomSubmitResult snapshot(String roomId, String sessionId, String wsNodeId);

	RoomSubmitResult drawStroke(String roomId, String sessionId, JsonNode stroke);

	RoomSubmitResult guessChat(String roomId, String sessionId, String text);

	RoomSubmitResult startGame(String roomId, String sessionId);

	RoomSubmitResult updateSetting(String roomId, String sessionId, JsonNode settingPayload);
}
