package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.dto.KopicEnvelope;
import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import io.jhpark.kopic.ge.room.dto.Participant;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRoomJobFactory implements RoomJobFactory {

	private static final String CLOSE_IF_EMPTY_TIMER_KEY = "close-if-empty";
	private static final Duration CLOSE_IF_EMPTY_DELAY = Duration.ofSeconds(30);

	private final CommonMapper commonMapper;
	private final OutboundBroadcaster roomBroadcaster;

	@Override
	public RoomJob join(String sessionId, String nickname, String wsNodeId) {
		return new RoomJob(
				room -> {

					log.info("join requested. roomId={}, sessionId={}, nickname={}", room.getRoomId(), sessionId,
							nickname);

					// 검증
					if (room.getParticipants().containsKey(sessionId)) {
						log.warn("session already exists. roomId={}, sessionId={}", room.getRoomId(), sessionId);
						return RoomJob.FollowUpResult.none();
					}

					Map<String, Participant> participants = room.getParticipants();
					participants.put(sessionId, new Participant(wsNodeId, sessionId, nickname));

					log.info("joined participant. roomId={}, sessionId={}, nickname={}", room.getRoomId(), sessionId,
							nickname);
					for (Participant participant : participants.values()) {
						sendToParticipant(participant, 301, Map.of(
								"sessionId", sessionId,
								"nickname", nickname));
					}

					log.info("current room participants: {}", room.getParticipants().keySet());

					return RoomJob.FollowUpResult.cancelTimer(CLOSE_IF_EMPTY_TIMER_KEY);
				});
	}

	@Override
	public RoomJob leave(String sessionId) {
		return new RoomJob(
				room -> {
					log.info("leave requested. roomId={}, sessionId={}", room.getRoomId(), sessionId);

					Map<String, Participant> participants = room.getParticipants();

					Participant removed = participants.remove(sessionId);
					if (removed == null) {
						return RoomJob.FollowUpResult.none();
					}

					if (participants.isEmpty()) {
						return RoomJob.FollowUpResult.followUp(
								closeIfEmpty(),
								CLOSE_IF_EMPTY_DELAY,
								CLOSE_IF_EMPTY_TIMER_KEY);
					} else {

						for (Participant participant : participants.values()) {
							sendToParticipant(participant, 302, Map.of(
									"sessionId", sessionId,
									"nickname", removed.nickname()));
						}

						return RoomJob.FollowUpResult.none();
					}

					
				});
	}

	@Override
	public RoomJob snapshot(String sessionId, String requestId) {
		return new RoomJob(
				room -> {
					log.info("snapshot requested. roomId={}, sessionId={}, requestId={}",
							room.getRoomId(), sessionId, requestId);
					Participant participant = room.getParticipants().get(sessionId);
					if (participant == null) {
						return RoomJob.FollowUpResult.none();
					}
					sendToParticipant(participant, 2102, RoomSnapshot.from(room));
					return RoomJob.FollowUpResult.none();
				});
	}

	@Override
	public RoomJob closeIfEmpty() {
		return new RoomJob(
				room -> RoomJob.FollowUpResult.requestCloseIfEmpty());
	}

	@Override
	public RoomJob updateGameSettings(String requestedSessionId, Map<String, Object> settings) {
		return notImplemented("updateGameSettings");
	}

	@Override
	public RoomJob gameStart(String requestedSessionId) {
		return notImplemented("gameStart");
	}

	@Override
	public RoomJob startRound(int roundNo) {
		return notImplemented("startRound");
	}

	@Override
	public RoomJob startWordChoiceTurn(int roundNo, int turnCursor) {
		return notImplemented("startWordChoiceTurn");
	}

	@Override
	public RoomJob openWordChoiceWindow(String expectedTurnId) {
		return notImplemented("openWordChoiceWindow");
	}

	@Override
	public RoomJob explicitWordChoice(String sessionId, int choiceIndex) {
		return notImplemented("explicitWordChoice");
	}

	@Override
	public RoomJob wordChoiceTimeout(String expectedTurnId) {
		return notImplemented("wordChoiceTimeout");
	}

	@Override
	public RoomJob drawingTimeout(String expectedTurnId) {
		return notImplemented("drawingTimeout");
	}

	@Override
	public RoomJob turnEnd(String expectedTurnId, String endReason) {
		return notImplemented("turnEnd");
	}

	@Override
	public RoomJob roundEnd(int expectedRoundNo) {
		return notImplemented("roundEnd");
	}

	@Override
	public RoomJob gameEnd() {
		return notImplemented("gameEnd");
	}

	@Override
	public RoomJob resultViewEnd() {
		return notImplemented("resultViewEnd");
	}

	@Override
	public RoomJob drawStroke(String sessionId, JsonNode stroke) {
		return new RoomJob(
				room -> {
					for (Participant participant : room.getParticipants().values()) {
						if (!participant.sessionId().equals(sessionId)) {
							sendToParticipant(participant, 201, stroke);
						}
					}
					return RoomJob.FollowUpResult.none();
				}
			);
	}

	@Override
	public RoomJob drawClear(String sessionId, String expectedTurnId) {
		return notImplemented("drawClear");
	}

	@Override
	public RoomJob guessSubmit(String sessionId, String expectedTurnId, String text) {
		return notImplemented("guessSubmit");
	}

	private RoomJob notImplemented(String jobName) {
		return new RoomJob(
				room -> {
					log.warn("room job not implemented yet. job={}, roomId={}", jobName, room.getRoomId());
					return RoomJob.FollowUpResult.none();
				});
	}

	private void sendToParticipant(Participant participant, int eventCode, Object payload) {
		JsonNode payloadNode = commonMapper.rawMapper().valueToTree(payload);

		roomBroadcaster.send(
				participant.wsNodeId(),
				new GeEvent(
						participant.sessionId(),
						new KopicEnvelope(eventCode, payloadNode),
						Instant.now().toString()));
	}

}
