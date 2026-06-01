package io.jhpark.kopic.ge.room.service;


import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;

public interface RoomJobFactory {

	RoomJob join(String sessionId, String nickname, String wsNodeId);

	RoomJob leave(String sessionId);

	RoomJob explicitWordChoice(String sessionId, int choiceIndex);

	RoomJob drawStroke(String sessionId, JsonNode stroke);

	RoomJob guessChat(String sessionId, String text);

	RoomJob updateSetting(String sessionId, JsonNode settingPayload);

	RoomJob startGame(String sessionId);

	RoomJob notify(String message);

	RoomJob drainAfterNotify(String message, Duration delay, RoomJob nextJob, String timerKey);

	RoomJob startDrain(Duration waitingRoomDeleteDelay);

	RoomJob forceClose(String message);
}
