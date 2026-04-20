package io.jhpark.kopic.ge.room.service;


import com.fasterxml.jackson.databind.JsonNode;

public interface RoomJobFactory {

	RoomJob join(String sessionId, String nickname, String wsNodeId);

	RoomJob leave(String sessionId);

	RoomJob closeIfEmpty();

	RoomJob nextRound();

	RoomJob nextTurn();

	RoomJob openWordChoiceWindow(String expectedTurnId);

	RoomJob explicitWordChoice(String sessionId, int choiceIndex);

	RoomJob wordChoiceTimeout(String expectedTurnId);

	RoomJob drawingTimeout(String expectedTurnId);

	RoomJob turnEnd(String expectedTurnId, String endReason);

	RoomJob roundEnd(int expectedRoundNo);

	RoomJob gameEnd();

	RoomJob resultViewEnd();

	RoomJob drawStroke(String sessionId, JsonNode stroke);

	RoomJob guessChat(String sessionId, String text);

	RoomJob updateSetting(String sessionId, JsonNode settingPayload);

	RoomJob startGame(String sessionId);
}
