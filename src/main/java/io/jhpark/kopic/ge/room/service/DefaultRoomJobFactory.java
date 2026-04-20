package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.dto.KopicEnvelope;
import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.common.util.TimeFormatUtil;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import io.jhpark.kopic.ge.room.dto.DrawerOrderMode;
import io.jhpark.kopic.ge.room.dto.EndMode;
import io.jhpark.kopic.ge.room.dto.Game;
import io.jhpark.kopic.ge.room.dto.Participant;
import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.Setting;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRoomJobFactory implements RoomJobFactory {

	private static final String CLOSE_IF_EMPTY_TIMER_KEY = "close-if-empty";
	private static final Duration CLOSE_IF_EMPTY_DELAY = Duration.ofSeconds(30);
	private static final String START_ROUND_TIMER_KEY = "start-round";
	private static final Duration START_ROUND_DELAY = Duration.ofSeconds(3);
	private static final String WORD_CHOICE_TIMER_KEY = "word-choice";
	private static final String DRAWING_TIMER_KEY = "drawing";
	private static final String TURN_RESULT_TIMER_KEY = "turn-result";
	private static final Duration TURN_RESULT_DELAY = Duration.ofSeconds(2);
	private static final List<String> DEFAULT_WORD_POOL = List.of(
		"사과",
		"바나나",
		"카메라",
		"성",
		"커피",
		"기타",
		"헬멧",
		"섬",
		"정글",
		"주방",
		"사다리",
		"랜턴",
		"산",
		"노트",
		"오렌지",
		"연필",
		"로켓",
		"스쿠터",
		"거북이",
		"창문"
	);

	private final CommonMapper commonMapper;
	private final GeEventPublisher geEventPublisher;

	// 참가자 입장을 처리하고 스냅샷/입장 이벤트를 전파한다.
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

					Participant newParticipant = new Participant(
						wsNodeId,
						sessionId,
						nickname,
						TimeFormatUtil.now()
					);
					
					Map<String, Participant> participants = room.getParticipants();
					if (participants.size() >= room.getCapacity()) {
						sendErrorToParticipant(newParticipant, 1999, "ROOM_FULL", "room is full");
						return RoomJob.FollowUpResult.none();
					}

					participants.put(sessionId, newParticipant);
					sendToParticipant(newParticipant, 408, Map.of(
						"sid", sessionId,
						"rid", room.getRoomId(),
						"snap", RoomSnapshot.from(room)));

					log.info("joined participant. roomId={}, sessionId={}, nickname={}", room.getRoomId(), sessionId,
							nickname);
					for (Participant participant : participants.values()) {
						sendToParticipant(participant, 301, Map.of(
								"sessionId", sessionId,
								"nickname", nickname));
					}

					log.info("current room participants: {}", room.getParticipants().keySet());

					RoomJob.FollowUpAction followUpAction = resolveQuickJoinCandidateAction(room);
					return new RoomJob.FollowUpResult(
						null,
						CLOSE_IF_EMPTY_TIMER_KEY,
						followUpAction
					);
				});
	}

	// 참가자 퇴장 처리, 호스트 이관, 빈 방 종료 예약을 수행한다.
	@Override
	public RoomJob leave(String sessionId) {
		return new RoomJob(
				room -> {
					log.info("leave requested. roomId={}, sessionId={}", room.getRoomId(), sessionId);

					Map<String, Participant> participants = room.getParticipants();
					int beforeSize = participants.size();

					Participant removed = participants.remove(sessionId);
					if (removed == null) {
						log.warn("leave ignored because session was not in room. roomId={}, sessionId={}",
							room.getRoomId(), sessionId);
						return RoomJob.FollowUpResult.none();
					}
					log.info("participant removed from room. roomId={}, sessionId={}, beforeCount={}, afterCount={}",
						room.getRoomId(), sessionId, beforeSize, participants.size());

					Game game = room.getGame();
					if (game != null && game.isPlaying()) {
						game.removeParticipant(sessionId);
					}

					String currentHostSessionId = null;
					if (sessionId.equals(room.getHostSessionId()) && !participants.isEmpty()) {
						Participant nextHost = selectNextHostParticipant(participants);
						if (nextHost != null) {
							currentHostSessionId = nextHost.sessionId();
							room.transferHost(currentHostSessionId);
							log.info(
								"room host transferred after leave. roomId={}, previousHost={}, currentHost={}",
								room.getRoomId(),
								sessionId,
								currentHostSessionId
							);
						}
					}

					boolean wasFull = room.getRoomType() == Room.QUICK_ROOM_TYPE
						&& beforeSize >= room.getCapacity();
					RoomJob.FollowUp followUp = null;
					if (participants.isEmpty()) {
						log.info(
							"room became empty after leave. roomId={}, sessionId={}, closeDelaySeconds={}",
							room.getRoomId(),
							sessionId,
							CLOSE_IF_EMPTY_DELAY.toSeconds()
						);
						followUp = new RoomJob.FollowUp(
							closeIfEmpty(),
							CLOSE_IF_EMPTY_DELAY,
							CLOSE_IF_EMPTY_TIMER_KEY
						);
					} else {
						log.info("leave broadcast sent. roomId={}, leftSessionId={}, remainingParticipants={}",
							room.getRoomId(), sessionId, participants.size());
						for (Participant participant : participants.values()) {
							Map<String, Object> payload = new HashMap<>();
							payload.put("sid", sessionId);
							if (currentHostSessionId != null) {
								payload.put("nextHost", currentHostSessionId);
							}
							sendToParticipant(participant, 302, payload);
						}
					}
					RoomJob.FollowUpAction followUpAction = wasFull
						? RoomJob.FollowUpAction.ADD_QUICK_JOIN_CANDIDATE
						: RoomJob.FollowUpAction.NONE;
					return new RoomJob.FollowUpResult(followUp, null, followUpAction);
				});
	}

	// 방이 비어 있으면 액터 종료 액션을 요청한다.
	@Override
	public RoomJob closeIfEmpty() {
		return new RoomJob(
				room -> {
					if (!room.getParticipants().isEmpty()) {
						log.info("close-if-empty skipped in job. roomId={}, participantCount={}",
							room.getRoomId(), room.getParticipants().size());
						return RoomJob.FollowUpResult.none();
					}
					return RoomJob.FollowUpResult.requestCloseIfEmpty();
				});
	}

	// 게임 시작 조건을 검증한 뒤 게임을 생성하고 첫 라운드 시작 잡을 예약한다.
	@Override
	public RoomJob startGame(String sessionId) {
		return new RoomJob(
			room -> {
				Participant requestedParticipant = resolveParticipant(room, sessionId);
				if (requestedParticipant == null) {
					return RoomJob.FollowUpResult.none();
				}

				// 방장만 게임시작 가능.
				if (rejectIfNotHost(room, requestedParticipant, "only host can start game")) {
					return RoomJob.FollowUpResult.none();
				}

				// 2명이상일때 시작가능.
				if (room.getParticipants().size() < 2) {
					sendErrorToParticipant(
						requestedParticipant,
						1999,
						"INVALID_REQUEST",
						"at least 2 participants required to start game"
					);
					return RoomJob.FollowUpResult.none();
				}

				
				Game currentGame = room.getGame();
				if (currentGame != null) {
					sendErrorToParticipant(
						requestedParticipant,
						1999,
						"CONFLICT",
						"game is already active"
					);
					return RoomJob.FollowUpResult.none();
				}

				List<String> participantSids = resolveParticipantSessionIds(room);
				if (participantSids.isEmpty()) {
					sendErrorToParticipant(
						requestedParticipant,
						1999,
						"INVALID_REQUEST",
						"no participants available to start game"
					);
					return RoomJob.FollowUpResult.none();
				}

				Game newGame = Game.start(room.getSetting().copy(), participantSids);
				room.startGame(newGame);
				room.getCurrentCanvas().clear();

				Map<String, Object> payload = new HashMap<>();
				payload.put("sid", sessionId);
				payload.put("gid", newGame.getGameId());
				payload.put("settings", newGame.getGameSetting().toPayload());

				for (Participant participant : room.getParticipants().values()) {
					sendToParticipant(participant, 200, payload); // 게임 시작 알림
				}

				log.info(
					"game started. roomId={}, gameId={}, hostSessionId={}, participantCount={}, nextRoundDelaySec={}",
					room.getRoomId(),
					newGame.getGameId(),
					sessionId,
					room.getParticipants().size(),
					START_ROUND_DELAY.toSeconds()
				);
				return RoomJob.FollowUpResult.followUp(
					nextRound(),
					START_ROUND_DELAY,
					START_ROUND_TIMER_KEY
				);
			}
		);
	}

	// 다음 라운드를 시작하고 즉시 다음 턴 준비 잡을 연결한다.
	@Override
	public RoomJob nextRound() {
		return new RoomJob(
			room -> {
				Game game = room.getGame();
				if (game == null || !game.isPlaying()) {
					log.warn("nextRound ignored because game is not active. roomId={}", room.getRoomId());
					return RoomJob.FollowUpResult.none();
				}

				int nextRoundNo = game.getCurRoundIndex() + 1;

				if (nextRoundNo > game.getGameSetting().roundCount()) {
					log.warn(
						"nextRound ignored because roundNo exceeds setting. roomId={}, roundNo={}, roundCount={}",
						room.getRoomId(),
						nextRoundNo,
						game.getGameSetting().roundCount()
					);
					return RoomJob.FollowUpResult.none();
				}

				List<String> roundDrawerSids = resolveRoundDrawerSids(room);
				if (roundDrawerSids.isEmpty()) {
					log.warn("nextRound ignored because drawer list is empty. roomId={}, roundNo={}", room.getRoomId(), nextRoundNo);
					return RoomJob.FollowUpResult.none();
				}

				game.startRound(nextRoundNo, roundDrawerSids);
				room.getCurrentCanvas().clear();

				Map<String, Object> payload = new HashMap<>();
				payload.put("gid", game.getGameId());
				payload.put("round", game.getCurRoundIndex());
				payload.put("roundId", game.getCurRoundId());
				payload.put("turn", game.getCurTurnId());
				payload.put("turnIndex", game.getCurTurnIndex());
				payload.put("drawerSid", game.getCurDrawerSid());
				payload.put("drawerSids", game.getCurRoundDrawerSids());

				for (Participant participant : room.getParticipants().values()) {
					sendToParticipant(participant, 202, payload);
				}

				log.info(
					"round started. roomId={}, gameId={}, roundNo={}, drawerSids={}",
					room.getRoomId(),
					game.getGameId(),
					game.getCurRoundIndex(),
					roundDrawerSids
				);
				return RoomJob.FollowUpResult.followUp(
					nextTurn(),
					null,
					null
				);
			}
		);
	}

	// 턴 인덱스를 계산해 턴 상태를 초기화하고 단어 선택 단계로 진입시킨다.
	@Override
	public RoomJob nextTurn() {
		return new RoomJob(
			room -> {
				Game game = room.getGame();
				if (game == null || !game.isPlaying()) {
					log.warn("nextTurn ignored because game is not active. roomId={}", room.getRoomId());
					return RoomJob.FollowUpResult.none();
				}
				if (game.getCurRoundDrawerSids() == null || game.getCurRoundDrawerSids().isEmpty()) {
					log.warn("nextTurn ignored because round drawer list is empty. roomId={}", room.getRoomId());
					return RoomJob.FollowUpResult.none();
				}

				Game.TurnPhase currentTurnPhase = game.getTurnPhase();
				if (currentTurnPhase != Game.TurnPhase.STARTING && currentTurnPhase != Game.TurnPhase.TURN_RESULT) {
					log.warn(
						"nextTurn ignored because turn phase is not transitionable. roomId={}, turnPhase={}",
						room.getRoomId(),
						currentTurnPhase
					);
					return RoomJob.FollowUpResult.none();
				}

				int nextTurnIndex = currentTurnPhase == Game.TurnPhase.STARTING
					? game.getCurTurnIndex()
					: game.getCurTurnIndex() + 1;
				if (nextTurnIndex < 0 || nextTurnIndex >= game.getCurRoundDrawerSids().size()) {
					log.warn(
						"nextTurn ignored because turn index is out of range. roomId={}, turnIndex={}, maxTurnIndex={}",
						room.getRoomId(),
						nextTurnIndex,
						game.getCurRoundDrawerSids().size() - 1
					);
					return RoomJob.FollowUpResult.none();
				}

				// nextTurn은 턴 식별자/순서만 준비하고 실제 시작은 단어 선택 단계로 넘긴다.
				game.startTurn(nextTurnIndex);
				room.getCurrentCanvas().clear();

				log.info(
					"turn prepared. roomId={}, gameId={}, roundNo={}, turnId={}, turnIndex={}, drawerSid={}",
					room.getRoomId(),
					game.getGameId(),
					game.getCurRoundIndex(),
					game.getCurTurnId(),
					game.getCurTurnIndex(),
					game.getCurDrawerSid()
				);
				return RoomJob.FollowUpResult.followUp(
					openWordChoiceWindow(game.getCurTurnId()),
					null,
					null
				);
			}
		);
	}

	// 단어 선택창을 열고 선택 타이머를 등록한다(후보 단어는 그리는 사람에게만 노출).
	@Override
	public RoomJob openWordChoiceWindow(String expectedTurnId) {
		return new RoomJob(
			room -> {
				Game game = room.getGame();
				if (game == null || !game.isPlaying()) {
					log.warn("openWordChoiceWindow ignored because game is not active. roomId={}", room.getRoomId());
					return RoomJob.FollowUpResult.none();
				}
				if (isBlank(expectedTurnId) || !expectedTurnId.equals(game.getCurTurnId())) {
					log.warn(
						"openWordChoiceWindow ignored due to turnId mismatch. roomId={}, expectedTurnId={}, currentTurnId={}",
						room.getRoomId(),
						expectedTurnId,
						game.getCurTurnId()
					);
					return RoomJob.FollowUpResult.none();
				}
				if (game.getTurnPhase() != Game.TurnPhase.STARTING && game.getTurnPhase() != Game.TurnPhase.WORD_CHOICE) {
					log.warn(
						"openWordChoiceWindow ignored because turn phase is invalid. roomId={}, turnPhase={}",
						room.getRoomId(),
						game.getTurnPhase()
					);
					return RoomJob.FollowUpResult.none();
				}

				// 단어 후보는 현재 룸 설정값을 기준으로 턴마다 새로 만든다.
				List<String> words = resolveWordChoices(game.getGameSetting().wordChoiceCount());
				game.openWordChoice(words);
				int choiceSec = normalizePositiveSeconds(game.getGameSetting().wordChoiceSec(), 10);

				for (Participant participant : room.getParticipants().values()) {
					Map<String, Object> payload = new HashMap<>();
					payload.put("gid", game.getGameId());
					payload.put("round", game.getCurRoundIndex());
					payload.put("roundId", game.getCurRoundId());
					payload.put("turn", game.getCurTurnId());
					payload.put("turnIndex", game.getCurTurnIndex());
					payload.put("drawerSid", game.getCurDrawerSid());
					payload.put("turnPhase", game.getTurnPhase().name());
					payload.put("wordChoiceSec", choiceSec);
					// 현재 그리는 사람에게만 단어 후보 전체를 전달한다.
					if (participant.sessionId().equals(game.getCurDrawerSid())) {
						payload.put("words", words);
					}
					sendToParticipant(participant, 203, payload);
				}

				log.info(
					"word choice opened. roomId={}, gameId={}, roundNo={}, turnId={}, drawerSid={}, choiceCount={}, choiceSec={}",
					room.getRoomId(),
					game.getGameId(),
					game.getCurRoundIndex(),
					game.getCurTurnId(),
					game.getCurDrawerSid(),
					words.size(),
					choiceSec
				);
				return RoomJob.FollowUpResult.followUp(
					wordChoiceTimeout(game.getCurTurnId()),
					Duration.ofSeconds(choiceSec),
					WORD_CHOICE_TIMER_KEY
				);
			}
		);
	}

	// 그리는 사람이 선택한 단어 인덱스를 검증하고 DRAWING 단계로 전환한다.
	@Override
	public RoomJob explicitWordChoice(String sessionId, int choiceIndex) {
		return new RoomJob(
			room -> {
				Game game = room.getGame();
				if (game == null || !game.isPlaying()) {
					log.warn("explicitWordChoice ignored because game is not active. roomId={}, sessionId={}",
						room.getRoomId(), sessionId);
					return RoomJob.FollowUpResult.none();
				}
				if (game.getTurnPhase() != Game.TurnPhase.WORD_CHOICE) {
					log.debug(
						"explicitWordChoice ignored because turn is not in word-choice phase. roomId={}, turnId={}, turnPhase={}",
						room.getRoomId(),
						game.getCurTurnId(),
						game.getTurnPhase()
					);
					return RoomJob.FollowUpResult.none();
				}

				Participant chooser = resolveParticipant(room, sessionId);
				if (chooser == null) {
					return RoomJob.FollowUpResult.none();
				}
				if (rejectIfNotCurrentDrawer(game, chooser, "only current drawer can choose word")) {
					return RoomJob.FollowUpResult.none();
				}
				if (choiceIndex < 0 || choiceIndex >= game.getWords().size()) {
					sendErrorToParticipant(
						chooser,
						1999,
						"INVALID_REQUEST",
						"choiceIndex out of range"
					);
					return RoomJob.FollowUpResult.none();
				}

				// 직접 선택이 들어오면 즉시 DRAWING으로 전환하고 단어선택 타이머를 취소한다.
				return startDrawingPhase(room, game, choiceIndex, "EXPLICIT");
			}
		);
	}

	// 단어 선택 시간 만료 시 후보 중 하나를 자동 선택해 DRAWING으로 전환한다.
	@Override
	public RoomJob wordChoiceTimeout(String expectedTurnId) {
		return new RoomJob(
			room -> {
				Game game = room.getGame();
				if (game == null || !game.isPlaying()) {
					log.warn("wordChoiceTimeout ignored because game is not active. roomId={}, expectedTurnId={}",
						room.getRoomId(), expectedTurnId);
					return RoomJob.FollowUpResult.none();
				}
				if (isBlank(expectedTurnId) || !expectedTurnId.equals(game.getCurTurnId())) {
					log.warn(
						"wordChoiceTimeout ignored due to turnId mismatch. roomId={}, expectedTurnId={}, currentTurnId={}",
						room.getRoomId(),
						expectedTurnId,
						game.getCurTurnId()
					);
					return RoomJob.FollowUpResult.none();
				}
				if (game.getTurnPhase() != Game.TurnPhase.WORD_CHOICE || game.getWords().isEmpty()) {
					log.debug(
						"wordChoiceTimeout ignored because turn is not in word-choice phase. roomId={}, turnId={}, turnPhase={}",
						room.getRoomId(),
						game.getCurTurnId(),
						game.getTurnPhase()
					);
					return RoomJob.FollowUpResult.none();
				}

				// 선택 시간 내 입력이 없으면 후보 중 하나를 강제로 선택한다.
				int autoChoiceIndex = ThreadLocalRandom.current().nextInt(game.getWords().size());
				return startDrawingPhase(room, game, autoChoiceIndex, "TIMEOUT_AUTO_PICK");
			}
		);
	}

	// 그리기 시간이 만료되면 턴 종료로 연결한다.
	@Override
	public RoomJob drawingTimeout(String expectedTurnId) {
		return new RoomJob(
			room -> {
				Game game = room.getGame();
				if (game == null || !game.isPlaying()) {
					log.warn("drawingTimeout ignored because game is not active. roomId={}, expectedTurnId={}",
						room.getRoomId(), expectedTurnId);
					return RoomJob.FollowUpResult.none();
				}
				if (isBlank(expectedTurnId) || !expectedTurnId.equals(game.getCurTurnId())) {
					log.warn(
						"drawingTimeout ignored due to turnId mismatch. roomId={}, expectedTurnId={}, currentTurnId={}",
						room.getRoomId(),
						expectedTurnId,
						game.getCurTurnId()
					);
					return RoomJob.FollowUpResult.none();
				}
				if (game.getTurnPhase() != Game.TurnPhase.DRAWING) {
					return RoomJob.FollowUpResult.none();
				}
				// turnId와 phase를 함께 확인해서 오래된 타이머 실행을 자연스럽게 무시한다.
				return RoomJob.FollowUpResult.followUp(
					turnEnd(game.getCurTurnId(), "DRAWING_TIMEOUT"),
					null,
					null
				);
			}
		);
	}

	// 턴 결과를 전파하고 결과 화면 유지 후 다음 턴/라운드 잡을 예약한다.
	@Override
	public RoomJob turnEnd(String expectedTurnId, String endReason) {
		return new RoomJob(
			room -> {
				Game game = room.getGame();
				if (game == null || !game.isPlaying()) {
					log.warn("turnEnd ignored because game is not active. roomId={}, expectedTurnId={}", room.getRoomId(), expectedTurnId);
					return RoomJob.FollowUpResult.none();
				}
				if (isBlank(expectedTurnId) || !expectedTurnId.equals(game.getCurTurnId())) {
					log.warn(
						"turnEnd ignored due to turnId mismatch. roomId={}, expectedTurnId={}, currentTurnId={}",
						room.getRoomId(),
						expectedTurnId,
						game.getCurTurnId()
					);
					return RoomJob.FollowUpResult.none();
				}
				if (game.getTurnPhase() == Game.TurnPhase.TURN_RESULT) {
					log.debug("turnEnd ignored because turn is already in result phase. roomId={}, turnId={}", room.getRoomId(), game.getCurTurnId());
					return RoomJob.FollowUpResult.none();
				}

				game.finishTurnResult();

				Map<String, Object> payload = new HashMap<>();
				payload.put("gid", game.getGameId());
				payload.put("round", game.getCurRoundIndex());
				payload.put("roundId", game.getCurRoundId());
				payload.put("turn", game.getCurTurnId());
				payload.put("turnIndex", game.getCurTurnIndex());
				payload.put("drawerSid", game.getCurDrawerSid());
				payload.put("reason", endReason);
				payload.put("answer", game.getAnswerWord());
				payload.put("earnedPoints", new HashMap<>(game.getEarnedPoints()));

				for (Participant participant : room.getParticipants().values()) {
					sendToParticipant(participant, 205, payload);
				}

				RoomJob nextJob = game.hasNextTurn() ? nextTurn() : nextRound();
				log.info(
					"turn ended. roomId={}, gameId={}, roundNo={}, turnId={}, endReason={}, nextAction={}, resultDelaySec={}",
					room.getRoomId(),
					game.getGameId(),
					game.getCurRoundIndex(),
					game.getCurTurnId(),
					endReason,
					game.hasNextTurn() ? "nextTurn" : "nextRound",
					TURN_RESULT_DELAY.toSeconds()
				);
				// 정답 조기 종료 같은 경우를 위해 진행 중인 drawing 타이머를 취소한다.
				return new RoomJob.FollowUpResult(
					new RoomJob.FollowUp(
						nextJob,
						TURN_RESULT_DELAY,
						TURN_RESULT_TIMER_KEY
					),
					DRAWING_TIMER_KEY,
					RoomJob.FollowUpAction.NONE
				);
			}
		);
	}

	// 라운드 종료 처리 확장 포인트(현재 미구현).
	@Override
	public RoomJob roundEnd(int expectedRoundNo) {
		return notImplemented("roundEnd");
	}

	// 게임 종료 처리 확장 포인트(현재 미구현).
	@Override
	public RoomJob gameEnd() {
		return notImplemented("gameEnd");
	}

	// 결과 화면 종료 처리 확장 포인트(현재 미구현).
	@Override
	public RoomJob resultViewEnd() {
		return notImplemented("resultViewEnd");
	}

	// 드로잉 입력을 캔버스에 반영하고 발신자를 제외한 참가자에게 전파한다.
	@Override
	public RoomJob drawStroke(String sessionId, JsonNode stroke) {
		return new RoomJob(
				room -> {
					if (stroke != null && stroke.isArray()) {
						boolean clearCanvas = stroke.size() > 0
							&& stroke.get(0).canConvertToInt()
							&& stroke.get(0).asInt() == 3;

						if (clearCanvas) {
							room.getCurrentCanvas().clear();
						} else {
							room.getCurrentCanvas().add(stroke);
						}

						for (Participant participant : room.getParticipants().values()) {
							if (!participant.sessionId().equals(sessionId)) {
								sendToParticipant(participant, 201, stroke);
							}
						}
					}
					return RoomJob.FollowUpResult.none();
					
				}
			);
	}

	// 일반 채팅 전파와 정답 판정/점수 반영/종료 조건 트리거를 함께 처리한다.
	@Override
	public RoomJob guessChat(String sessionId, String text) {
		return new RoomJob(
			room -> {
				// 방에 없는 세션이면 채팅/정답 판정을 진행하지 않는다.
				Participant sender = resolveParticipant(room, sessionId);
				if (sender == null) {
					return RoomJob.FollowUpResult.none();
				}

				Game game = room.getGame();
				boolean inDrawingPhase = game != null
					&& game.isPlaying()
					&& game.getTurnPhase() == Game.TurnPhase.DRAWING;

				// 로비/라운드 대기/결과 화면 등 DRAWING 외 구간은 일반 채팅으로 전체 전파한다.
				if (!inDrawingPhase) {
					broadcastChatToAllExceptSender(room, sessionId, text);
					return RoomJob.FollowUpResult.none();
				}

				// 그리는 사람 채팅은 힌트 유출 방지를 위해 sealed 채널로만 전파한다.
				if (sessionId.equals(game.getCurDrawerSid())) {
					broadcastSealedChat(room, game, sessionId, text);
					return RoomJob.FollowUpResult.none();
				}

				boolean alreadyCorrect = game.getEarnedPoints().containsKey(sessionId);
				if (isBlank(text) || isBlank(game.getAnswerWord())) {
					if (alreadyCorrect) {
						// 이미 정답자는 정답자+그리는 사람에게만 비공개 채팅으로 전송한다.
						broadcastSealedChat(room, game, sessionId, text);
					} else {
						broadcastChatToAllExceptSender(room, sessionId, text);
					}
					return RoomJob.FollowUpResult.none();
				}

				String normalizedText = normalizeText(text);
				String normalizedAnswer = normalizeText(game.getAnswerWord());

				// 정답이 아니면 일반 채팅 전파. 단, 이미 정답자는 정답자+그리는 사람에게만 보인다.
				if (!normalizedText.equals(normalizedAnswer)) {
					if (alreadyCorrect) {
						broadcastSealedChat(room, game, sessionId, text);
					} else {
						broadcastChatToAllExceptSender(room, sessionId, text);
					}
					return RoomJob.FollowUpResult.none();
				}

				// 정답 메시지는 채팅으로 방송하지 않고 점수/종료 판정만 처리한다.
				Integer existingPoint = game.getEarnedPoints().putIfAbsent(sessionId, 1);
				if (existingPoint != null) {
					return RoomJob.FollowUpResult.none();
				}
				game.getTotalPoints().merge(sessionId, 1, Integer::sum);

				boolean shouldEndTurn = false;
				String endReason = "CORRECT_ANSWER";
				EndMode endMode = game.getGameSetting().endMode();
				if (endMode == EndMode.FIRST_CORRECT) {
					shouldEndTurn = true;
					endReason = "FIRST_CORRECT";
				} else if (endMode == EndMode.TIME_OR_ALL_CORRECT && allGuessersSolved(room, game)) {
					shouldEndTurn = true;
					endReason = "ALL_CORRECT";
				}
				if (!shouldEndTurn) {
					return RoomJob.FollowUpResult.none();
				}

				return RoomJob.FollowUpResult.followUp(
					turnEnd(game.getCurTurnId(), endReason),
					null,
					null
				);
			}
		);
	}

	// 발신자를 제외한 모든 참가자에게 일반 채팅을 전파한다.
	private void broadcastChatToAllExceptSender(Room room, String sessionId, String text) {
		for (Participant participant : room.getParticipants().values()) {
			if (!participant.sessionId().equals(sessionId)) {
				sendToParticipant(participant, 204, Map.of(
					"sid", sessionId,
					"t", text
				));
			}
		}
	}

	// sealed 채팅을 정답자+그리는 사람에게만 전파한다.
	private void broadcastSealedChat(Room room, Game game, String sessionId, String text) {
		for (Participant participant : room.getParticipants().values()) {
			if (participant.sessionId().equals(sessionId)) {
				continue;
			}
			boolean visibleToParticipant = participant.sessionId().equals(game.getCurDrawerSid())
				|| game.getEarnedPoints().containsKey(participant.sessionId());
			if (!visibleToParticipant) {
				continue;
			}
			sendToParticipant(participant, 204, Map.of(
				"sid", sessionId,
				"t", text,
				"sealed", 1
			));
		}
	}

	// 단어 선택 단계를 종료하고 DRAWING 단계 시작 이벤트/타이머를 구성한다.
	private RoomJob.FollowUpResult startDrawingPhase(
		Room room,
		Game game,
		int choiceIndex,
		String selectionReason
	) {
		// 단어 직접 선택/시간초과 선택 모두 이 경로에서 DRAWING으로 전환한다.
		game.startDrawing(choiceIndex);
		int drawSec = normalizePositiveSeconds(game.getGameSetting().drawSec(), 40);

		for (Participant participant : room.getParticipants().values()) {
			Map<String, Object> payload = new HashMap<>();
			payload.put("gid", game.getGameId());
			payload.put("round", game.getCurRoundIndex());
			payload.put("roundId", game.getCurRoundId());
			payload.put("turn", game.getCurTurnId());
			payload.put("turnIndex", game.getCurTurnIndex());
			payload.put("drawerSid", game.getCurDrawerSid());
			payload.put("turnPhase", game.getTurnPhase().name());
			payload.put("drawSec", drawSec);
			payload.put("selectionReason", selectionReason);
			// 정답 텍스트는 그리는 사람에게만 주고, 나머지는 글자 수 힌트만 준다.
			if (participant.sessionId().equals(game.getCurDrawerSid())) {
				payload.put("answer", game.getAnswerWord());
			} else if (game.getAnswerWord() != null) {
				payload.put("answerLength", game.getAnswerWord().length());
			}
			sendToParticipant(participant, 203, payload);
		}

		log.info(
			"drawing started. roomId={}, gameId={}, roundNo={}, turnId={}, drawerSid={}, drawSec={}, selectionReason={}",
			room.getRoomId(),
			game.getGameId(),
			game.getCurRoundIndex(),
			game.getCurTurnId(),
			game.getCurDrawerSid(),
			drawSec,
			selectionReason
		);

		return new RoomJob.FollowUpResult(
			new RoomJob.FollowUp(
				drawingTimeout(game.getCurTurnId()),
				Duration.ofSeconds(drawSec),
				DRAWING_TIMER_KEY
			),
			// 직접 선택/자동 선택 시 남아있는 단어선택 타이머를 반드시 무효화한다.
			WORD_CHOICE_TIMER_KEY,
			RoomJob.FollowUpAction.NONE
		);
	}

	// 요청 개수에 맞게 임시 단어 풀에서 후보 목록을 만든다.
	private List<String> resolveWordChoices(int requestedCount) {
		// 외부 사전 공급자가 붙기 전까지 임시 단어 풀을 사용한다.
		int targetCount = requestedCount <= 0 ? 1 : requestedCount;
		List<String> pool = new ArrayList<>(DEFAULT_WORD_POOL);
		Collections.shuffle(pool);
		if (targetCount <= pool.size()) {
			return List.copyOf(pool.subList(0, targetCount));
		}
		List<String> words = new ArrayList<>(targetCount);
		for (int index = 0; index < targetCount; index++) {
			words.add(pool.get(index % pool.size()));
		}
		return words;
	}

	// 초 단위 설정값이 0 이하일 때 기본값으로 보정한다.
	private int normalizePositiveSeconds(int seconds, int defaultValue) {
		if (seconds <= 0) {
			return defaultValue;
		}
		return seconds;
	}

	// 세션 ID로 참가자를 조회한다(없으면 null).
	private Participant resolveParticipant(Room room, String sessionId) {
		if (room == null || isBlank(sessionId)) {
			return null;
		}
		return room.getParticipants().get(sessionId);
	}

	// 현재 요청한 참가자가 방장인지 확인하고 아니면 에러를 전송한다.
	private boolean rejectIfNotHost(Room room, Participant participant, String message) {
		if (participant == null) {
			return true;
		}
		if (participant.sessionId().equals(room.getHostSessionId())) {
			return false;
		}
		sendErrorToParticipant(
			participant,
			1999,
			"FORBIDDEN",
			message
		);
		return true;
	}

	// 현재 요청한 참가자가 이번 턴의 그리는 사람인지 확인하고 아니면 에러를 전송한다.
	private boolean rejectIfNotCurrentDrawer(Game game, Participant participant, String message) {
		if (game == null || participant == null) {
			return true;
		}
		if (participant.sessionId().equals(game.getCurDrawerSid())) {
			return false;
		}
		sendErrorToParticipant(
			participant,
			1999,
			"FORBIDDEN",
			message
		);
		return true;
	}

	// 현재 턴에서 그리는 사람을 제외한 모든 사람이 정답했는지 확인한다.
	private boolean allGuessersSolved(Room room, Game game) {
		int requiredGuessers = 0;
		int solvedGuessers = 0;
		for (Participant participant : room.getParticipants().values()) {
			if (participant.sessionId().equals(game.getCurDrawerSid())) {
				continue;
			}
			requiredGuessers++;
			if (game.getEarnedPoints().containsKey(participant.sessionId())) {
				solvedGuessers++;
			}
		}
		if (requiredGuessers <= 0) {
			return false;
		}
		return solvedGuessers >= requiredGuessers;
	}

	// null/공백 문자열 여부를 확인한다.
	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	// 정답 비교용으로 채팅 텍스트를 정규화한다.
	private String normalizeText(String value) {
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	// 미구현 잡 호출 시 경고만 남기고 무시한다.
	private RoomJob notImplemented(String jobName) {
		return new RoomJob(
				room -> {
					log.warn("room job not implemented yet. job={}, roomId={}", jobName, room.getRoomId());
					return RoomJob.FollowUpResult.none();
				});
	}

	// 퀵조인 후보 목록에 방을 추가/제거할 후속 액션을 결정한다.
	private RoomJob.FollowUpAction resolveQuickJoinCandidateAction(Room room) {
		if (room.getRoomType() != Room.QUICK_ROOM_TYPE) {
			return RoomJob.FollowUpAction.NONE;
		}
		if (room.getParticipants().size() >= room.getCapacity()) {
			return RoomJob.FollowUpAction.REMOVE_QUICK_JOIN_CANDIDATE;
		}
		return RoomJob.FollowUpAction.ADD_QUICK_JOIN_CANDIDATE;
	}

	// 참가자를 입장 시간 순으로 정렬해 세션 ID 목록을 만든다.
	private List<String> resolveParticipantSessionIds(Room room) {
		List<String> participantSids = new ArrayList<>();
		room.getParticipants()
			.values()
			.stream()
			.sorted(
				Comparator
					.comparing((Participant participant) -> parseJoinedAtOrMax(participant.joinedAt()))
					.thenComparing(Participant::sessionId)
			)
			.map(Participant::sessionId)
			.forEach(participantSids::add);
		return participantSids;
	}

	// 라운드 그릴 사람 순서를 설정값(입장순/랜덤)에 맞게 구성한다.
	private List<String> resolveRoundDrawerSids(Room room) {
		List<String> drawerSids = new ArrayList<>(resolveParticipantSessionIds(room));

		if (room.getSetting().drawerOrderMode() == DrawerOrderMode.RANDOM) {
			Collections.shuffle(drawerSids);
		}
		return drawerSids;
	}

	// 가장 먼저 입장한 참가자를 다음 방장으로 선택한다.
	private Participant selectNextHostParticipant(Map<String, Participant> participants) {
		return participants.values()
			.stream()
			.min(
				Comparator
					.comparing((Participant participant) -> parseJoinedAtOrMax(participant.joinedAt()))
					.thenComparing(Participant::sessionId)
			)
			.orElse(null);
	}

	// 입장 시각 파싱 실패 시 정렬 뒤로 밀기 위해 Instant.MAX를 반환한다.
	private Instant parseJoinedAtOrMax(String joinedAt) {
		if (joinedAt == null || joinedAt.isBlank()) {
			return Instant.MAX;
		}
		try {
			return TimeFormatUtil.parse(joinedAt);
		} catch (RuntimeException runtimeException) {
			log.warn("failed to parse joinedAt for host selection. joinedAt={}", joinedAt, runtimeException);
			return Instant.MAX;
		}
	}

	// 단일 참가자에게 이벤트 코드를 붙여 아웃바운드 이벤트를 발행한다.
	private void sendToParticipant(Participant participant, int eventCode, Object payload) {
		JsonNode payloadNode = payload instanceof JsonNode jsonNode
			? jsonNode
			: commonMapper.rawMapper().valueToTree(payload);

		geEventPublisher.publish(
				participant.wsNodeId(),
				new GeEvent(
						participant.sessionId(),
						new KopicEnvelope(eventCode, payloadNode),
						Instant.now().toString()));
	}

	// 단일 참가자에게 에러 이벤트를 발행한다.
	private void sendErrorToParticipant(Participant participant, int errorEventCode, String reason, String message) {
		geEventPublisher.publish(
				participant.wsNodeId(),
				new GeEvent(
						participant.sessionId(),
						new KopicEnvelope(
							errorEventCode,
							commonMapper.rawMapper().valueToTree(
								Map.of(
									"reason", reason,
									"message", message
								)
							)
						),
						TimeFormatUtil.now()
				)
		);
	}

	// 방장 권한으로 설정을 갱신하고 다른 참가자에게 변경 사항을 전파한다.
	@Override
	public RoomJob updateSetting(String requestedSessionId, JsonNode settingPayload) {
		return new RoomJob(
			room -> {
				Participant requestedParticipant = resolveParticipant(room, requestedSessionId);
				if (requestedParticipant == null) {
					return RoomJob.FollowUpResult.none();
				}

				if (rejectIfNotHost(room, requestedParticipant, "only host can update game setting")) {
					return RoomJob.FollowUpResult.none();
				}

				try {
					Setting parsedSetting = Setting.fromPayload(settingPayload);
					room.updateSetting(parsedSetting);
				} catch (IllegalArgumentException illegalArgumentException) {
					sendErrorToParticipant(
						requestedParticipant,
						1999,
						"INVALID_REQUEST",
						illegalArgumentException.getMessage()
					);
					return RoomJob.FollowUpResult.none();
				}

				for (Participant participant : room.getParticipants().values()) {
					if(!participant.sessionId().equals(requestedSessionId)) {
						sendToParticipant(participant, 107, settingPayload);
					}
				}
				return RoomJob.FollowUpResult.none();
			}
		);
	}

	

}
