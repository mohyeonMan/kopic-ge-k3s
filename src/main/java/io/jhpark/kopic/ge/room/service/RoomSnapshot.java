package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.util.TimeFormatUtil;
import io.jhpark.kopic.ge.room.dto.Game;
import io.jhpark.kopic.ge.room.dto.Participant;
import io.jhpark.kopic.ge.room.dto.Room;
import java.time.Instant;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.annotation.JsonProperty;

public record RoomSnapshot(
	@JsonProperty("rc") String roomCode,
	@JsonProperty("rt") int roomType,
	@JsonProperty("hs") String hostSessionId,
	@JsonProperty("st") List<Object> settings,
	@JsonProperty("pc") int participantCount,
	@JsonProperty("ps") Map<String, ParticipantSnapshot> participants,
	@JsonProperty("cv") List<JsonNode> currentCanvas,
	@JsonProperty("g") GameSnapshot game,
	@JsonProperty("now") Long serverNowMs,
	@JsonProperty("dl") Long deadlineAtMs
) {

	public static RoomSnapshot from(Room room) {
		Map<String, ParticipantSnapshot> copiedParticipants = snapshotParticipants(room.getParticipants());
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
			room.getRoomType(),
			room.getHostSessionId(),
			room.getSetting().toPayload(),
			copiedParticipants.size(),
			copiedParticipants,
			copiedCanvas,
			GameSnapshot.from(room),
			serverNowMs,
			deadlineAtMs
		);
	}

	private static Map<String, ParticipantSnapshot> snapshotParticipants(Map<String, Participant> participants) {
		LinkedHashMap<String, ParticipantSnapshot> copiedParticipants = new LinkedHashMap<>();
		if (participants == null || participants.isEmpty()) {
			return Collections.unmodifiableMap(copiedParticipants);
		}
		participants.values().stream()
			.sorted(
				Comparator
					.comparing((Participant participant) -> parseJoinedAtOrMax(participant.joinedAt()))
					.thenComparing(Participant::sessionId)
			)
			.forEach(participant -> copiedParticipants.put(
				participant.sessionId(),
				new ParticipantSnapshot(
					participant.sessionId(),
					participant.nickname(),
					participant.colorIndex()
				)
			));
		return Collections.unmodifiableMap(copiedParticipants);
	}

	private static Instant resolveDeadlineAt(Room room) {
		Game game = room.getGame();
		if (game != null) {
			return game.getDeadlineAt();
		}
		return room.getAutoRestartAt();
	}

	private static Instant parseJoinedAtOrMax(String joinedAt) {
		if (joinedAt == null || joinedAt.isBlank()) {
			return Instant.MAX;
		}
		try {
			return TimeFormatUtil.parse(joinedAt);
		} catch (RuntimeException runtimeException) {
			return Instant.MAX;
		}
	}

	public record ParticipantSnapshot(
		@JsonProperty("sid") String sessionId,
		@JsonProperty("n") String nickname,
		@JsonProperty("ci") int colorIndex
	) {
	}

	public record GameSnapshot(
		@JsonProperty("gid") String gid,
		@JsonProperty("gp") Game.GamePhase gamePhase,
		@JsonProperty("r") int round,
		@JsonProperty("ri") String roundId,
		@JsonProperty("rp") Game.RoundPhase roundPhase,
		@JsonProperty("dss") List<String> drawerSids,
		@JsonProperty("ca") List<String> correctAnswerSids,
		@JsonProperty("tid") String turn,
		@JsonProperty("tp") Game.TurnPhase turnPhase,
		@JsonProperty("ds") String drawerSid,
		@JsonProperty("al") Integer answerLength,
		@JsonProperty("hp") String hintPattern,
		@JsonProperty("ans") String answer,
		@JsonProperty("ep") Map<String, Integer> earnedPoints,
		@JsonProperty("pts") Map<String, Integer> totalPoints
	) {

		static GameSnapshot from(Room room) {
			Game game = room == null ? null : room.getGame();
			if (game == null) {
				return null;
			}
			Integer answerLength = null;
			String hintPattern = null;
			String answer = null;
			List<String> drawerSids = null;
			List<String> correctAnswerSids = List.of();
			Map<String, Integer> earnedPoints = null;
			if (game.getCurRoundDrawerSids() != null) {
				drawerSids = List.copyOf(game.getCurRoundDrawerSids());
			}
			if (room != null
				&& game.getEarnedPoints() != null
				&& !game.getEarnedPoints().isEmpty()) {
				correctAnswerSids = room.getParticipants().values().stream()
					.sorted(
						Comparator
							.comparing((Participant participant) -> parseJoinedAtOrMax(participant.joinedAt()))
							.thenComparing(Participant::sessionId)
					)
					.map(Participant::sessionId)
					.filter(sessionId -> !sessionId.equals(game.getCurDrawerSid()))
					.filter(sessionId -> game.getEarnedPoints().containsKey(sessionId))
					.toList();
			}
			if (game.getTurnPhase() == Game.TurnPhase.DRAWING && game.getAnswerWord() != null) {
				answerLength = game.getAnswerWord().length();
				hintPattern = game.getHintPattern();
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
				drawerSids,
				correctAnswerSids,
				game.getCurTurnId(),
				game.getTurnPhase(),
				game.getCurDrawerSid(),
				answerLength,
				hintPattern,
				answer,
				earnedPoints,
				totalPoints
			);
		}
	}
}
