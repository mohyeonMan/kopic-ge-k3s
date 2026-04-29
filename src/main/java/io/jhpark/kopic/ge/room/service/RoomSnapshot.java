package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.Game;
import io.jhpark.kopic.ge.room.dto.Participant;
import io.jhpark.kopic.ge.room.dto.Room;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

public record RoomSnapshot(
	String roomCode,
	String hostSessionId,
	List<Integer> settings,
	int participantCount,
	Map<String, Participant> participants,
	List<JsonNode> currentCanvas,
	GameSnapshot game,
	Long serverNowMs,
	Long deadlineAtMs
) {

	public static RoomSnapshot from(Room room) {
		Map<String, Participant> copiedParticipants =
			Collections.unmodifiableMap(new LinkedHashMap<>(room.getParticipants()));
		List<JsonNode> copiedCanvas = List.copyOf(room.getCurrentCanvas());
		Instant deadlineAt = resolveDeadlineAt(room);
		Long serverNowMs = null;
		Long deadlineAtMs = null;
		if (deadlineAt != null) {
			serverNowMs = Instant.now().toEpochMilli();
			deadlineAtMs = deadlineAt.toEpochMilli();
		}
		return new RoomSnapshot(
			room.getRoomCode(),
			room.getHostSessionId(),
			room.getSetting().toPayload(),
			copiedParticipants.size(),
			copiedParticipants,
			copiedCanvas,
			GameSnapshot.from(room.getGame()),
			serverNowMs,
			deadlineAtMs
		);
	}

	private static Instant resolveDeadlineAt(Room room) {
		Game game = room.getGame();
		if (game != null) {
			return game.getDeadlineAt();
		}
		return room.getAutoRestartAt();
	}

	public record GameSnapshot(
		String gid,
		Game.GamePhase gamePhase,
		int round,
		String roundId,
		Game.RoundPhase roundPhase,
		String turn,
		Game.TurnPhase turnPhase,
		String drawerSid,
		Integer answerLength,
		String answer,
		Map<String, Integer> earnedPoints,
		Map<String, Integer> totalPoints
	) {

		static GameSnapshot from(Game game) {
			if (game == null) {
				return null;
			}
			Integer answerLength = null;
			String answer = null;
			Map<String, Integer> earnedPoints = null;
			if (game.getTurnPhase() == Game.TurnPhase.DRAWING && game.getAnswerWord() != null) {
				answerLength = game.getAnswerWord().length();
			}
			if (game.getTurnPhase() == Game.TurnPhase.TURN_RESULT) {
				if (game.getAnswerWord() != null && !game.getAnswerWord().isBlank()) {
					answer = game.getAnswerWord();
				}
				if (game.getEarnedPoints() != null && !game.getEarnedPoints().isEmpty()) {
					earnedPoints = Collections.unmodifiableMap(new LinkedHashMap<>(game.getEarnedPoints()));
				}
			}
			Map<String, Integer> totalPoints = null;
			if (game.getTotalPoints() != null && !game.getTotalPoints().isEmpty()) {
				totalPoints = Collections.unmodifiableMap(new LinkedHashMap<>(game.getTotalPoints()));
			}
			return new GameSnapshot(
				game.getGameId(),
				game.getGamePhase(),
				game.getCurRoundIndex(),
				game.getCurRoundId(),
				game.getRoundPhase(),
				game.getCurTurnId(),
				game.getTurnPhase(),
				game.getCurDrawerSid(),
				answerLength,
				answer,
				earnedPoints,
				totalPoints
			);
		}
	}
}
