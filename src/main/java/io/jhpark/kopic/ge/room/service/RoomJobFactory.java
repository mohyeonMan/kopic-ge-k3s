package io.jhpark.kopic.ge.room.service;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

public interface RoomJobFactory {

	RoomJob join(String sessionId, String nickname, String wsNodeId);

	RoomJob leave(String sessionId);

	RoomJob snapshot(String sessionId);

	RoomJob closeIfEmpty();

	RoomJob updateGameSettings(String requestedSessionId, Map<String, Object> settings);

	RoomJob gameStart(String requestedSessionId);

	RoomJob startRound(int roundNo);

	RoomJob startWordChoiceTurn(int roundNo, int turnCursor);

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
}
