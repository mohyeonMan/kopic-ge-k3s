package io.jhpark.kopic.ge.room.service;


import com.fasterxml.jackson.databind.JsonNode;

public interface RoomJobFactory {

	RoomJob join(String sessionId, String nickname, String wsNodeId);

	RoomJob leave(String sessionId);

	RoomJob explicitWordChoice(String sessionId, int choiceIndex);

	RoomJob drawStroke(String sessionId, JsonNode stroke);

	RoomJob guessChat(String sessionId, String text);

	RoomJob updateSetting(String sessionId, JsonNode settingPayload);

	RoomJob startGame(String sessionId);
}
