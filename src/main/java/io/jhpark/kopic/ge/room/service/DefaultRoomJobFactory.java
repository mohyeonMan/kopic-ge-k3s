package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.config.GameTimerProperties;
import io.jhpark.kopic.ge.common.dto.KopicEnvelope;
import io.jhpark.kopic.ge.common.metrics.GeMetrics;
import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.common.util.TimeFormatUtil;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import io.jhpark.kopic.ge.room.dto.CustomWordMode;
import io.jhpark.kopic.ge.room.dto.DrawerOrderMode;
import io.jhpark.kopic.ge.room.dto.EndMode;
import io.jhpark.kopic.ge.room.dto.Game;
import io.jhpark.kopic.ge.room.dto.Participant;
import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.Setting;
import io.jhpark.kopic.ge.room.dto.WordEntry;
import io.jhpark.kopic.ge.room.dto.Game.GamePhase;
import io.jhpark.kopic.ge.room.dto.Game.RoundPhase;
import io.jhpark.kopic.ge.room.dto.Game.TurnPhase;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRoomJobFactory implements RoomJobFactory {

	private static final String CLOSE_IF_EMPTY_TIMER_KEY = "close-if-empty";
	private static final String GAME_TIMER_KEY_PREFIX = "game:";
	private static final String GAME_TIMER_CLEAR_KEY = GAME_TIMER_KEY_PREFIX + "*";
	private static final String START_ROUND_TIMER_KEY = GAME_TIMER_KEY_PREFIX + "start-round";
	private static final String NEXT_TURN_TIMER_KEY = GAME_TIMER_KEY_PREFIX + "next-turn";
	private static final String OPEN_WORD_CHOICE_TIMER_KEY = GAME_TIMER_KEY_PREFIX + "open-word-choice";
	private static final String WORD_CHOICE_TIMER_KEY = GAME_TIMER_KEY_PREFIX + "word-choice";
	private static final String DRAWING_PHASE_TIMER_KEY_PREFIX = GAME_TIMER_KEY_PREFIX + "drawing-phase:";
	private static final String DRAWING_TIMER_KEY = DRAWING_PHASE_TIMER_KEY_PREFIX + "timeout";
	private static final String HINT_REVEAL_TIMER_KEY = DRAWING_PHASE_TIMER_KEY_PREFIX + "hint";
	private static final String DRAWING_PHASE_TIMER_CLEAR_KEY = DRAWING_PHASE_TIMER_KEY_PREFIX + "*";
	private static final String TURN_RESULT_TIMER_KEY = GAME_TIMER_KEY_PREFIX + "turn-result";
	private static final String GAME_RESULT_TIMER_KEY = GAME_TIMER_KEY_PREFIX + "game-result";
	private static final String QUICK_RESTART_TIMER_KEY = GAME_TIMER_KEY_PREFIX + "quick-restart";
	private static final String RETURN_TO_LOBBY_REASON_RESULT_END = "RESULT_END";
	private static final String RETURN_TO_LOBBY_REASON_NOT_ENOUGH_PARTICIPANTS = "NOT_ENOUGH_PARTICIPANTS";
	private static final int MIN_COLOR_INDEX = 1;
	private static final int MAX_COLOR_INDEX = 20;

	private final CommonMapper commonMapper;
	private final GeEventPublisher geEventPublisher;
	private final WordPoolProvider wordPoolProvider;
	private final GameTimerProperties gameTimerProperties;
	private final GeMetrics geMetrics;

	/**
	 * 참가자 입장 요청을 처리한다.
	 * 중복 세션, 정원 초과를 먼저 검증한 뒤 방 상태에 참가자를 반영하고
	 * 입장자에게는 스냅샷(408), 기존 참가자에게는 입장 알림(301)을 전파한다.
	 * 또한 퀵조인 후보군 갱신과 기존 close-if-empty 타이머 취소를 후속 결과로 반환한다.
	 */
	@Override
	public RoomJob join(String sessionId, String nickname, String wsNodeId) {
		return new RoomJob(
				room -> {
					// 1) 입장 요청 로그를 남기고, 세션/정원 검증부터 진행한다.
					// 2) 검증 통과 시 참가자를 방 상태에 반영한다.
					// 3) 신규 참가자에게는 스냅샷(408), 기존 참가자에게는 입장 알림(301)을 전송한다.
					// 4) 마지막으로 close-if-empty 타이머 취소 및 퀵조인 후보 동기화 액션을 반환한다.

					log.info("join requested. roomId={}, sessionId={}, nickname={}", room.getRoomId(), sessionId,
							nickname);

					// 같은 세션이 이미 방에 있으면 중복 입장으로 간주해 즉시 종료한다.
					if (room.getParticipants().containsKey(sessionId)) {
						log.warn("session already exists. roomId={}, sessionId={}", room.getRoomId(), sessionId);
						return RoomJob.FollowUpResult.none();
					}

					Map<String, Participant> participants = room.getParticipants();
					// 정원 초과인 경우 참가자 추가 없이 에러 이벤트만 응답한다.
					if (participants.size() >= room.getCapacity()) {
						sendErrorToSession(wsNodeId, sessionId, 1999, "ROOM_FULL", "room is full");
						return RoomJob.FollowUpResult.none();
					}

					Participant newParticipant = new Participant(
						wsNodeId,
						sessionId,
						nickname,
						resolveNextColorIndex(participants),
						TimeFormatUtil.now()
					);

					// 방 상태에 참가자를 반영한 뒤, 입장자 본인에게 최신 스냅샷을 전달한다.
					participants.put(sessionId, newParticipant);
					sendToParticipant(newParticipant, 408, Map.of(
						"sid", sessionId,
						"rid", room.getRoomId(),
						"snap", RoomSnapshot.from(room)));

					log.info("joined participant. roomId={}, sessionId={}, nickname={}", room.getRoomId(), sessionId,
							nickname);
					// 새 참가자 정보를 방의 모든 참가자에게 브로드캐스트한다.
					for (Participant participant : participants.values()) {
						sendToParticipant(participant, 301, Map.of(
								"sessionId", sessionId,
								"nickname", nickname,
								"colorIndex", newParticipant.colorIndex()));
					}

					log.info("current room participants: {}", room.getParticipants().keySet());

					// 입장으로 인해 비어있지 않게 되었으므로 close-if-empty 타이머는 취소 대상으로 반환한다.
					RoomJob.FollowUp followUp = null;
					boolean shouldAutoStartQuickGame =
						room.getRoomType() == Room.QUICK_ROOM_TYPE
							&& room.getGame() == null
							&& room.getAutoRestartAt() == null
							&& room.getParticipants().size() >= 2;
					if (shouldAutoStartQuickGame) {
						log.info(
							"quick room auto start scheduled after join. roomId={}, triggerSessionId={}, participantCount={}",
							room.getRoomId(),
							sessionId,
							room.getParticipants().size()
						);
						followUp = new RoomJob.FollowUp(
							startGame(null),
							null,
							null
						);
					}
					RoomJob.FollowUpAction followUpAction = 
						room.getRoomType() == Room.QUICK_ROOM_TYPE &&
						room.getParticipants().size() >= room.getCapacity() ?
						RoomJob.FollowUpAction.REMOVE_QUICK_JOIN_CANDIDATE :
						RoomJob.FollowUpAction.NONE;

					return new RoomJob.FollowUpResult(
						followUp,
						CLOSE_IF_EMPTY_TIMER_KEY,
						followUpAction
					);
				});
	}

	/**
	 * 참가자 퇴장 요청을 처리한다.
	 * 참가자를 방에서 제거하고, 필요 시 방장 이관과 퀵조인 후보 상태 갱신을 수행한다.
	 * 게임 진행 중 현재 그리는 사람이 나가면 턴 강제 종료 follow-up을 연결하고
	 * 관련 타이머(word-choice/drawing) 취소 키도 함께 반환한다.
	 */
	@Override
	public RoomJob leave(String sessionId) {
		return new RoomJob(
			room -> {
				// 1) 참가자를 제거하고, 없는 세션이면 즉시 종료한다.
				// 2) 빈 방이면 게임/호스트 상태를 정리하고 close-if-empty만 예약한다.
				// 3) 비어있지 않으면 방장 이양, 게임 보정, leave 브로드캐스트를 처리한다.
				log.info("leave requested. roomId={}, sessionId={}", room.getRoomId(), sessionId);

				Map<String, Participant> participants = room.getParticipants();
				int beforeSize = participants.size();

				// 방에 없는 세션의 퇴장은 무시한다.
				Participant removed = participants.remove(sessionId);
				if (removed == null) {
					log.warn("leave ignored because session was not in room. roomId={}, sessionId={}",
						room.getRoomId(), sessionId);
					return RoomJob.FollowUpResult.none();
				}
				log.info("participant removed from room. roomId={}, sessionId={}, beforeCount={}, afterCount={}",
					room.getRoomId(), sessionId, beforeSize, participants.size());

				boolean isQuickRoom = room.getRoomType() == Room.QUICK_ROOM_TYPE;
				boolean wasFull = room.getRoomType() == Room.QUICK_ROOM_TYPE
					&& beforeSize >= room.getCapacity();

				// 빈 방이 되면 다른 진행 로직은 생략하고 종료 예약만 남긴다.
				if (participants.isEmpty()) {
					Game game = room.getGame();
					String cancelTimerKey = room.getAutoRestartAt() != null
						? QUICK_RESTART_TIMER_KEY
						: null;
					if (game != null) {
						// stale game 상태를 남기지 않도록 게임/타이머를 같이 정리한다.
						cancelTimerKey = GAME_TIMER_CLEAR_KEY;
						game.removeParticipant(sessionId);
						room.endGame();
					}
					room.clearAutoRestartAt();
					room.transferHost(null);
					room.getCurrentCanvas().clear();
						log.info(
							"room became empty after leave. roomId={}, sessionId={}, closeDelaySeconds={}",
							room.getRoomId(),
							sessionId,
							gameTimerProperties.closeIfEmpty().toSeconds()
						);
						return new RoomJob.FollowUpResult(
							new RoomJob.FollowUp(
								closeIfEmpty(),
								gameTimerProperties.closeIfEmpty(),
								CLOSE_IF_EMPTY_TIMER_KEY
							),
							cancelTimerKey,
						isQuickRoom
							? RoomJob.FollowUpAction.REMOVE_QUICK_JOIN_CANDIDATE
							: RoomJob.FollowUpAction.NONE
					);
				}

				String currentHostSessionId = null;
				if (sessionId.equals(room.getHostSessionId())) {
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

				Game game = room.getGame();
				RoomJob.FollowUp followUp = null;
				String cancelTimerKey = null;
				String returnToLobbyReason = null;
				String returnToLobbyGameId = null;
				if (game != null) {
					boolean wasCurrentDrawer = sessionId.equals(game.getCurDrawerSid());
					Game.TurnPhase turnPhase = game.getTurnPhase();
					String currentTurnId = game.getCurTurnId();
					game.removeParticipant(sessionId);

					// 게임은 2명 이상에서만 유지한다.
					if (participants.size() < 2) {
						cancelTimerKey = GAME_TIMER_CLEAR_KEY;
						returnToLobbyReason = RETURN_TO_LOBBY_REASON_NOT_ENOUGH_PARTICIPANTS;
						returnToLobbyGameId = game.getGameId();
						log.info(
							"game ended because participant count dropped below minimum. roomId={}, remainingParticipants={}",
							room.getRoomId(),
							participants.size()
						);
					} else if (game.isPlaying()
						&& wasCurrentDrawer
						&& turnPhase != Game.TurnPhase.TURN_RESULT
						&& !isBlank(currentTurnId)
					) {
						followUp = new RoomJob.FollowUp(
							turnEnd("DRAWER_LEFT"),
							null,
							null
						);
						cancelTimerKey = resolveTurnTimerCancelKey(turnPhase);
						log.info(
							"drawer left during active turn. roomId={}, turnId={}, turnPhase={}, forcedEndReason={}",
							room.getRoomId(),
							currentTurnId,
							turnPhase,
							"DRAWER_LEFT"
						);
					}
				} else if (room.getAutoRestartAt() != null && participants.size() < 2) {
					cancelTimerKey = QUICK_RESTART_TIMER_KEY;
					room.clearAutoRestartAt();
				}

				log.info("leave broadcast sent. roomId={}, leftSessionId={}, remainingParticipants={}",
					room.getRoomId(), sessionId, participants.size());
				for (Participant participant : participants.values()) {
					Map<String, Object> payload = currentHostSessionId == null
						? Map.of("sid", sessionId)
						: Map.of(
							"sid", sessionId,
							"nextHost", currentHostSessionId
						);
					sendToParticipant(participant, 302, payload);
				}
				if (!isBlank(returnToLobbyReason) && !isBlank(returnToLobbyGameId)) {
					clearGameAndBroadcastReturnToLobby(
						room,
						returnToLobbyGameId,
						returnToLobbyReason,
						false
					);
				}

				RoomJob.FollowUpAction followUpAction = wasFull
					? RoomJob.FollowUpAction.ADD_QUICK_JOIN_CANDIDATE
					: RoomJob.FollowUpAction.NONE;
				return new RoomJob.FollowUpResult(followUp, cancelTimerKey, followUpAction);
			});
	}

	/**
	 * 방이 비어 있는지 다시 확인한 뒤 액터 종료를 요청한다.
	 * close-if-empty 타이머 만료 후 실행되며, 중간에 재입장이 있었다면 아무 동작도 하지 않는다.
	 */
	private RoomJob closeIfEmpty() {
		return new RoomJob(
				room -> {
					// 타이머 실행 시점에 참가자가 비어 있는지 최종 확인한다.
					if (!room.getParticipants().isEmpty()) {
						log.info("close-if-empty skipped in job. roomId={}, participantCount={}",
							room.getRoomId(), room.getParticipants().size());
						return RoomJob.FollowUpResult.none();
					}
					return RoomJob.FollowUpResult.requestCloseIfEmpty();
				});
	}

	/**
	 * 게임 시작 요청을 처리한다.
	 * 요청자가 방장인지와 최소 인원 조건(2명 이상)을 검증한 뒤
	 * 게임 객체를 생성하고 게임 시작 이벤트(200)를 브로드캐스트한다.
	 * 실제 라운드 진입은 지연 후 nextRound follow-up으로 연결한다.
	 */
	@Override
	public RoomJob startGame(String sessionId) {
		return new RoomJob(
			room -> {
				// 시작 권한과 최소 인원 등을 검증한 뒤 게임을 생성하고 라운드 시작을 예약한다.
				boolean isQuickRoom = room.getRoomType() == Room.QUICK_ROOM_TYPE;
				boolean hadAutoRestartDeadline = room.getAutoRestartAt() != null;
				Participant requestedParticipant = resolveParticipant(room, sessionId);
				if (!isQuickRoom && requestedParticipant == null) {
					return RoomJob.FollowUpResult.none();
				}

				// 방장만 게임시작 가능.
				if (!isQuickRoom && rejectIfNotHost(room, requestedParticipant, "only host can start game")) {
					return RoomJob.FollowUpResult.none();
				}

				// 2명이상일때 시작가능.
				if (room.getParticipants().size() < 2) {
					if (isQuickRoom && requestedParticipant == null && hadAutoRestartDeadline) {
						room.clearAutoRestartAt();
					}
					if (requestedParticipant != null) {
						sendErrorToParticipant(
							requestedParticipant,
							1999,
							"INVALID_REQUEST",
							"at least 2 participants required to start game"
						);
					}
					return RoomJob.FollowUpResult.none();
				}
				if (rejectIfGameAlreadyExists(room, requestedParticipant, "CONFLICT", "game is already active")) {
					return RoomJob.FollowUpResult.none();
				}

				List<WordEntry> customWordPool = List.of();
				if (!isQuickRoom) {
					customWordPool = wordPoolProvider.parseCustomWordPool(
						room.getSetting().customWordsRaw()
					);
					if (room.getSetting().customWordMode() == CustomWordMode.CUSTOM_ONLY
						&& customWordPool.isEmpty()) {
						if (requestedParticipant != null) {
							sendErrorToParticipant(
								requestedParticipant,
								1999,
								"INVALID_REQUEST",
								"custom words are required when customWordMode is CUSTOM_ONLY"
							);
						}
						return RoomJob.FollowUpResult.none();
					}
				}
				Game newGame = room.startGame(customWordPool);
				geMetrics.increment(
					"kopic_ge_game_start_total",
					"room_type",
					metricRoomType(room.getRoomType()),
					"trigger",
					isBlank(sessionId) ? "system" : "request"
				);
				newGame.setDeadlineAt(Instant.now().plus(gameTimerProperties.startRound()));

				Map<String, Object> payload = Map.of(
					"gid", newGame.getGameId(),
					"gameStartSec", gameTimerProperties.startRound().toSeconds()
				);

				for (Participant participant : room.getParticipants().values()) {
					sendToParticipant(participant, 200, payload); // 게임 시작 알림
				}

				log.info(
					"game started. roomId={}, gameId={}, participantCount={}",
					room.getRoomId(),
					newGame.getGameId(),
					room.getParticipants().size()
				);
				return new RoomJob.FollowUpResult(
					new RoomJob.FollowUp(
						nextRound(),
						gameTimerProperties.startRound(),
						START_ROUND_TIMER_KEY
					),
					hadAutoRestartDeadline ? QUICK_RESTART_TIMER_KEY : null,
					RoomJob.FollowUpAction.NONE
				);
			}
		);
	}

	/**
	 * 다음 라운드를 시작한다.
	 * 현재 게임이 활성 상태인지, 다음 라운드가 남아 있는지 확인한 뒤
	 * 라운드 상태/그리는 순서를 초기화하고 라운드 시작 이벤트(202)를 전파한다.
	 * 라운드 시작 후 짧은 대기시간을 둔 뒤 nextTurn follow-up으로 이어진다.
	 */
	private RoomJob nextRound() {
		return new RoomJob(
			room -> {
				// 다음 라운드를 열고 라운드 시작 이벤트를 전파한 뒤 턴 준비로 이동한다.
				if (!validatePlayingRound(room, RoundPhase.READY)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();
				game.clearDeadlineAt();

				if (!game.hasNextRound()) {
					log.info(
						"nextRound ignored because no next round remains. roomId={}, curRoundNo={}, roundCount={}",
						room.getRoomId(),
						game.getCurRoundIndex(),
						game.getGameSetting().roundCount()
					);
					return RoomJob.FollowUpResult.none();
				}

				game.startRound(resolveRoundDrawerSids(room));

				Map<String, Object> payload = Map.of(
					"gid", game.getGameId(),
					"round", game.getCurRoundIndex(),
					"roundId", game.getCurRoundId(),
					"drawerSids", game.getCurRoundDrawerSids(),
					"roundStartSec", gameTimerProperties.nextTurn().toSeconds()
				);

				for (Participant participant : room.getParticipants().values()) {
					sendToParticipant(participant, 202, payload);
				}

				log.info(
					"round started. roomId={}, gameId={}, roundNo={}, drawerSids={}",
					room.getRoomId(),
					game.getGameId(),
					game.getCurRoundIndex(),
					game.getCurRoundDrawerSids()
				);
				game.setDeadlineAt(Instant.now().plus(gameTimerProperties.nextTurn()));
				
				return RoomJob.FollowUpResult.followUp(
					nextTurn(),
					gameTimerProperties.nextTurn(),
					NEXT_TURN_TIMER_KEY
				);
			}
		);
	}

	/**
	 * 다음 턴 준비를 수행한다.
	 * 현재 라운드의 drawer 목록을 기준으로 Game.startTurn() 전이를 시도하고
	 * 턴 시작 이벤트(209)를 전파한 뒤 짧은 대기시간 후 단어 선택 창 오픈 잡(openWordChoiceWindow)으로 연결한다.
	 * 전이 불가 상태(phase 불일치, 인덱스 범위 문제)는 예외를 잡아 무시한다.
	 */
	private RoomJob nextTurn() {
		return new RoomJob(
			room -> {
				// 턴 메타데이터를 준비하고 단어 선택 단계로 전환한다.
				if (!validatePlayingTurn(room, RoundPhase.PLAYING, TurnPhase.READY)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();
				game.clearDeadlineAt();

				if(!game.hasNextTurn()){
					log.info(
						"nextRound ignored because no next round remains. roomId={}, curRoundNo={}, roundCount={}",
						room.getRoomId(),
						game.getCurRoundIndex(),
						game.getGameSetting().roundCount()
					);
					return RoomJob.FollowUpResult.none();
				}

				game.startTurn();

				log.info(
					"turn prepared. roomId={}, gameId={}, roundNo={}, turnId={}, turnIndex={}, drawerSid={}",
					room.getRoomId(),
					game.getGameId(),
					game.getCurRoundIndex(),
					game.getCurTurnId(),
					game.getCurTurnIndex(),
					game.getCurDrawerSid()
				);
				game.setDeadlineAt(Instant.now().plus(gameTimerProperties.openWordChoice()));

				Map<String, Object> payload = Map.of(
					"gid", game.getGameId(),
					"round", game.getCurRoundIndex(),
					"turn", game.getCurTurnId(),
					"turnIndex", game.getCurTurnIndex(),
					"drawerSid", game.getCurDrawerSid(),
					"turnStartSec", gameTimerProperties.openWordChoice().toSeconds()
				);
				for (Participant participant : room.getParticipants().values()) {
					sendToParticipant(participant, 209, payload);
				}
				return RoomJob.FollowUpResult.followUp(
					openWordChoiceWindow(),
					gameTimerProperties.openWordChoice(),
					OPEN_WORD_CHOICE_TIMER_KEY
				);
			}
		);
	}

	/**
	 * 단어 선택 창을 연다.
	 * 현재 턴 상태가 단어 선택 가능인지 검증한 뒤 단어 선택 이벤트(203)를 전파한다.
	 * 후보 단어는 drawer에게만 포함해 이벤트(203)로 전파하며
	 * wordChoiceTimeout 타이머 follow-up을 등록한다.
	 */
	private RoomJob openWordChoiceWindow() {
		return new RoomJob(
			room -> {
				if (!validatePlayingTurn(room, RoundPhase.PLAYING, TurnPhase.STARTING)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();
				game.clearDeadlineAt();

				// 단어 후보는 현재 룸 설정값을 기준으로 턴마다 새로 만든다.
				List<WordEntry> wordEntries = resolveWordChoices(game, game.getGameSetting().wordChoiceCount());
				if (wordEntries.isEmpty()) {
					log.warn(
						"word choice skipped because candidates were empty. roomId={}, gameId={}, roundNo={}, turnId={}, customWordMode={}",
						room.getRoomId(),
						game.getGameId(),
						game.getCurRoundIndex(),
						game.getCurTurnId(),
						game.getGameSetting().customWordMode()
					);
					return RoomJob.FollowUpResult.followUp(turnEnd("NO_WORD_CANDIDATE"), null, null);
				}
				game.openWordCandidate(wordEntries);
				List<String> words = wordEntries.stream()
					.map(WordEntry::word)
					.toList();
				int choiceSec = normalizePositiveSeconds(game.getGameSetting().wordChoiceSec(), 10);
				game.setDeadlineAt(Instant.now().plusSeconds(choiceSec));

				Map<String, Object> payload =Map.of(
					"sid", game.getCurDrawerSid(),
					"wordChoiceSec", choiceSec
				);

				Map<String, Object> drawerPayload =Map.of(
					"sid", game.getCurDrawerSid(),
					"wordChoiceSec", choiceSec,
					"words", words
				);

				for (Participant participant : room.getParticipants().values()) {
					if (participant.sessionId().equals(game.getCurDrawerSid())) 
						sendToParticipant(participant, 203, drawerPayload);
					else
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
					wordChoiceTimeout(),
					Duration.ofSeconds(choiceSec),
					WORD_CHOICE_TIMER_KEY
				);
			}
		);
	}

	/**
	 * drawer의 명시적 단어 선택 요청을 처리한다.
	 * 요청자가 현재 drawer인지, choiceIndex가 후보 범위 내인지 검증한 뒤
	 * DRAWING 단계 전이(startDrawingPhase)를 수행한다.
	 * 게임 비활성 상태나 잘못된 요청은 에러 이벤트로 응답한다.
	 */
	@Override
	public RoomJob explicitWordChoice(String sessionId, int choiceIndex) {
		return new RoomJob(
			room -> {
				Participant chooser = resolveParticipant(room, sessionId);
				if (chooser == null) {
					return RoomJob.FollowUpResult.none();
				}
				if (!validatePlayingTurn(room, RoundPhase.PLAYING, TurnPhase.WORD_CHOICE)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();
				if (rejectIfNotCurrentDrawer(game, chooser, "only current drawer can choose word")) {
					return RoomJob.FollowUpResult.none();
				}

				// 직접 선택이 들어오면 즉시 DRAWING으로 전환하고 단어선택 타이머를 취소한다.
				return startDrawingPhase(room, game, choiceIndex, "EXPLICIT");
			}
		);
	}

	/**
	 * 단어 선택 시간 만료를 처리한다.
	 * 현재 턴이 WORD_CHOICE 상태인지 확인한 뒤 후보 단어 중 하나를 자동 선택하여 DRAWING 단계로 전환한다.
	 */
	private RoomJob wordChoiceTimeout() {
		return new RoomJob(
			room -> {
				// 단어 선택 시간이 지나면 후보 중 하나를 자동 선택한다.
				if (!validatePlayingTurn(room, RoundPhase.PLAYING, TurnPhase.WORD_CHOICE)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();
				return startDrawingPhase(room, game, null, "TIMEOUT_AUTO_PICK");
			}
		);
	}

	/**
	 * 그리기 시간 만료를 처리한다.
	 * 현재 턴이 DRAWING 상태인지 검증하고 유효한 경우 turnEnd("DRAWING_TIMEOUT") follow-up으로 턴 종료를 유도한다.
	 */
	private RoomJob drawingTimeout() {
		return new RoomJob(
			room -> {
				// 드로잉 시간이 종료되면 현재 턴을 결과 단계로 마무리한다.
				if (!validatePlayingTurn(room, RoundPhase.PLAYING, TurnPhase.DRAWING)) {
					return RoomJob.FollowUpResult.none();
				}
				return RoomJob.FollowUpResult.followUp(
					turnEnd("DRAWING_TIMEOUT"),
					null,
					null
				);
			}
		);
	}

	/**
	 * DRAWING 중 힌트를 주기적으로 공개한다.
	 * 설정값(hintRevealSec, hintLetterCount)에 따라 힌트를 공개하고,
	 * 더 공개할 글자가 남아있으면 같은 타이머 키로 다음 tick을 재예약한다.
	 */
	private RoomJob hintRevealTick() {
		return new RoomJob(
			room -> {
				if (!validatePlayingTurn(room, RoundPhase.PLAYING, TurnPhase.DRAWING)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();
				if (game == null || game.getGameSetting() == null) {
					return RoomJob.FollowUpResult.none();
				}
				int hintRevealSec = game.getGameSetting().hintRevealSec();
				int hintLetterCount = game.getGameSetting().hintLetterCount();
				if (hintRevealSec <= 0 || hintLetterCount <= 0) {
					return RoomJob.FollowUpResult.none();
				}

				int revealedCount = game.revealHintLetters(hintLetterCount);
				if (revealedCount > 0) {
					geMetrics.increment(
						"kopic_ge_hint_reveal_total",
						revealedCount
					);
					broadcastHintPattern(room, game);
				}
				if (!game.hasPendingHintReveals()) {
					return RoomJob.FollowUpResult.none();
				}

				return RoomJob.FollowUpResult.followUp(
					hintRevealTick(),
					Duration.ofSeconds(hintRevealSec),
					HINT_REVEAL_TIMER_KEY
				);
			}
		);
	}

	/**
	 * 턴 종료를 처리한다.
	 * 종료 가능한 턴 phase만 결과 phase로 전이하고 경량 결과 이벤트(205)를 전파한다.
	 * 결과 화면 유지 시간 이후 별도 follow-up에서 READY 전환과 다음 진행을 결정한다.
	 */
	private RoomJob turnEnd(String endReason) {
		return new RoomJob(
			room -> {
				// 턴 결과 상태를 먼저 확정하고, 다음 진행은 결과 화면 종료 후 최신 상태로 판단한다.
				if (!validatePlayingTurn(
					room,
					RoundPhase.PLAYING,
					TurnPhase.STARTING,
					TurnPhase.WORD_CHOICE,
					TurnPhase.DRAWING
				)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();
				TurnPhase turnPhase = game.getTurnPhase();

				String resolvedEndReason = isBlank(endReason) ? "UNKNOWN" : endReason;
				geMetrics.increment(
					"kopic_ge_turn_end_total",
					"reason",
					resolvedEndReason
				);
				// applyDrawerBonus(game);
				applyEarnedPointsToTotalPoints(game);
				game.consumeCurrentTurnDrawer();
				game.finishTurnResult();
				game.setDeadlineAt(Instant.now().plus(gameTimerProperties.turnResult()));
				if (!game.hasNextTurn()) {
					game.finishRoundResult();
				}

				long turnEndSec = gameTimerProperties.turnResult().toSeconds();
				Map<String, Object> payload = turnEndPayload(game, resolvedEndReason, turnEndSec);
				for (Participant participant : room.getParticipants().values()) {
					sendToParticipant(participant, 205, payload);
				}

				log.info(
					"turn result started. roomId={}, gameId={}, roundNo={}, turnId={}, endReason={}, resultDelaySec={}",
					room.getRoomId(),
					game.getGameId(),
					game.getCurRoundIndex(),
					game.getCurTurnId(),
					resolvedEndReason,
					turnEndSec
				);
				return new RoomJob.FollowUpResult(
					new RoomJob.FollowUp(
						turnResultEnd(),
						gameTimerProperties.turnResult(),
						TURN_RESULT_TIMER_KEY
					),
					resolveTurnTimerCancelKey(turnPhase),
					RoomJob.FollowUpAction.NONE
				);
			}
		);
	}

	private RoomJob turnResultEnd() {
		return new RoomJob(
			room -> {
				if (!validatePlayingGame(room)
					|| !validateTurnPhase(room, TurnPhase.TURN_RESULT)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();
				game.clearDeadlineAt();

				RoomJob nextJob;
				String nextAction;
				if (game.hasNextTurn()) {
					if (game.getRoundPhase() != RoundPhase.PLAYING) {
						log.warn(
							"turnResultEnd ignored because round is not playing before next turn. roomId={}, turnId={}, roundPhase={}",
							room.getRoomId(),
							game.getCurTurnId(),
							game.getRoundPhase()
						);
						return RoomJob.FollowUpResult.none();
					}
					game.readyNextTurn();
					nextJob = nextTurn();
					nextAction = "nextTurn";
				} else if (game.hasNextRound()) {
					if (game.getRoundPhase() == RoundPhase.PLAYING) {
						game.finishRoundResult();
					}
					if (game.getRoundPhase() != RoundPhase.FINISHED) {
						log.warn(
							"turnResultEnd ignored because round is not finished before next round. roomId={}, turnId={}, roundPhase={}",
							room.getRoomId(),
							game.getCurTurnId(),
							game.getRoundPhase()
						);
						return RoomJob.FollowUpResult.none();
					}
					game.readyNextRound();
					nextJob = nextRound();
					nextAction = "nextRound";
				} else {
					if (game.getRoundPhase() == RoundPhase.PLAYING) {
						game.finishRoundResult();
					}
					nextJob = gameEnd();
					nextAction = "gameEnd";
				}

				log.info(
					"turn result ended. roomId={}, gameId={}, roundNo={}, turnId={}, nextAction={}",
					room.getRoomId(),
					game.getGameId(),
					game.getCurRoundIndex(),
					game.getCurTurnId(),
					nextAction
				);
				return RoomJob.FollowUpResult.followUp(nextJob, null, null);
			}
		);
	}

	private Map<String, Object> turnEndPayload(Game game, String endReason, long turnEndSec) {
		boolean hasAnswer = !isBlank(game.getAnswerWord());
		boolean hasEarnedPoints = game.getEarnedPoints() != null && !game.getEarnedPoints().isEmpty();
		if (hasAnswer && hasEarnedPoints) {
			return Map.of(
				"gid", game.getGameId(),
				"turn", game.getCurTurnId(),
				"reason", endReason,
				"turnEndSec", turnEndSec,
				"answer", game.getAnswerWord(),
				"earnedPoints", Map.copyOf(game.getEarnedPoints())
			);
		}
		if (hasAnswer) {
			return Map.of(
				"gid", game.getGameId(),
				"turn", game.getCurTurnId(),
				"reason", endReason,
				"turnEndSec", turnEndSec,
				"answer", game.getAnswerWord()
			);
		}
		if (hasEarnedPoints) {
			return Map.of(
				"gid", game.getGameId(),
				"turn", game.getCurTurnId(),
				"reason", endReason,
				"turnEndSec", turnEndSec,
				"earnedPoints", Map.copyOf(game.getEarnedPoints())
			);
		}
		return Map.of(
			"gid", game.getGameId(),
			"turn", game.getCurTurnId(),
			"reason", endReason,
			"turnEndSec", turnEndSec
		);
	}

	/**
	 * 게임 결과 화면을 시작한다.
	 * 최종 점수 이벤트(206)를 전파하고 결과 화면 유지 후 resultViewEnd follow-up을 예약한다.
	 */
	private RoomJob gameEnd() {
		return new RoomJob(
			room -> {
				if (!validateGameExists(room)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();
				if (game.isGameResult()) {
					log.debug("gameEnd ignored because game is already in result phase. roomId={}, gameId={}",
						room.getRoomId(), game.getGameId());
					return RoomJob.FollowUpResult.none();
				}
				if (!game.isPlaying()) {
					log.warn(
						"gameEnd ignored because game is not playing. roomId={}, gameId={}, gamePhase={}",
						room.getRoomId(),
						game.getGameId(),
						game.getGamePhase()
					);
					return RoomJob.FollowUpResult.none();
				}
				if (game.hasNextTurn() || game.hasNextRound()) {
					log.warn(
						"gameEnd ignored because remaining game progress exists. roomId={}, gameId={}, hasNextTurn={}, hasNextRound={}",
						room.getRoomId(),
						game.getGameId(),
						game.hasNextTurn(),
						game.hasNextRound()
					);
					return RoomJob.FollowUpResult.none();
				}

				game.finishGameResult();
				game.setDeadlineAt(Instant.now().plus(gameTimerProperties.gameResult()));
				Map<String, Object> payload = gameResultPayload(game);
				for (Participant participant : room.getParticipants().values()) {
					sendToParticipant(participant, 206, payload);
				}

				log.info(
					"game result started. roomId={}, gameId={}, resultDelaySec={}",
					room.getRoomId(),
					game.getGameId(),
					gameTimerProperties.gameResult().toSeconds()
				);
				return RoomJob.FollowUpResult.followUp(
					resultViewEnd(),
					gameTimerProperties.gameResult(),
					GAME_RESULT_TIMER_KEY
				);
			}
		);
	}

	/**
	 * 현재 게임 결과 화면을 종료하고 로비로 복귀시킨다.
	 */
	private RoomJob resultViewEnd() {
		return new RoomJob(
			room -> {
				if (!validateGamePhase(room, GamePhase.GAME_RESULT)) {
					return RoomJob.FollowUpResult.none();
				}
				Game game = room.getGame();

				String gameId = game.getGameId();
				boolean quickRestart = shouldAutoRestartQuickGame(room);
				clearGameAndBroadcastReturnToLobby(
					room,
					gameId,
					RETURN_TO_LOBBY_REASON_RESULT_END,
					quickRestart
				);

				log.info(
					"game result ended. roomId={}, gameId={}, quickRestart={}, restartDelaySec={}",
					room.getRoomId(),
					gameId,
					quickRestart,
					quickRestart ? gameTimerProperties.quickRestart().toSeconds() : 0
				);
				if (!quickRestart) {
					return RoomJob.FollowUpResult.none();
				}
				return RoomJob.FollowUpResult.followUp(
					startGame(null),
					gameTimerProperties.quickRestart(),
					QUICK_RESTART_TIMER_KEY
				);
			}
		);
	}

	private Map<String, Object> gameResultPayload(Game game) {
		if (game.getTotalPoints() != null && !game.getTotalPoints().isEmpty()) {
			return Map.of(
				"gid", game.getGameId(),
				"resultSec", gameTimerProperties.gameResult().toSeconds(),
				"totalPoints", Map.copyOf(game.getTotalPoints())
			);
		}
		return Map.of(
			"gid", game.getGameId(),
			"resultSec", gameTimerProperties.gameResult().toSeconds()
		);
	}

	private Map<String, Object> returnToLobbyPayload(String gameId, String reason, boolean quickRestart) {
		if (quickRestart) {
			return Map.of(
				"gid", gameId,
				"reason", reason,
				"restartSec", gameTimerProperties.quickRestart().toSeconds()
			);
		}
		return Map.of(
			"gid", gameId,
			"reason", reason
		);
	}

	private void clearGameAndBroadcastReturnToLobby(
		Room room,
		String gameId,
		String reason,
		boolean quickRestart
	) {
		if (quickRestart) {
			room.setAutoRestartAt(Instant.now().plus(gameTimerProperties.quickRestart()));
		} else {
			room.clearAutoRestartAt();
		}
		Map<String, Object> payload = returnToLobbyPayload(gameId, reason, quickRestart);
		room.endGame();
		room.getCurrentCanvas().clear();
		for (Participant participant : room.getParticipants().values()) {
			sendToParticipant(participant, 207, payload);
		}
	}

	private boolean shouldAutoRestartQuickGame(Room room) {
		return room.getRoomType() == Room.QUICK_ROOM_TYPE
			&& room.getParticipants().size() >= 2;
	}

	/**
	 * 드로잉 스트로크 입력을 처리한다.
	 * clear 명령(코드 3)이면 캔버스를 비우고, 일반 스트로크면 캔버스 상태에 누적한다.
	 * 발신자를 제외한 참가자에게만 드로잉 이벤트(201)를 브로드캐스트한다.
	 */
	@Override
	public RoomJob drawStroke(String sessionId, JsonNode stroke) {
		return new RoomJob(
			room -> {
				Game game = room.getGame();
				boolean inPlayingGame = game != null && game.getGamePhase() == GamePhase.PLAYING;
				if (inPlayingGame) {
					if (game.getTurnPhase() != TurnPhase.DRAWING) {
						return RoomJob.FollowUpResult.none();
					}
					if (sessionId == null || !sessionId.equals(game.getCurDrawerSid())) {
						return RoomJob.FollowUpResult.none();
					}
				}

				// 스트로크를 캔버스에 반영하고 송신자를 제외한 참가자에게 전달한다.
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

	/**
	 * 채팅 메시지를 처리한다.
	 * DRAWING 구간이 아니면 일반 채팅 브로드캐스트만 수행하고,
	 * DRAWING 구간에서는 정답 판정, 시간 기반 점수 예약, 정답자 공지, 턴 종료 조건(첫 정답/전원 정답)을 함께 처리한다.
	 * 이미 정답한 사용자의 메시지는 sealed 채널 규칙으로 제한 전파한다.
	 */
	@Override
	public RoomJob guessChat(String sessionId, String text) {
		return new RoomJob(
			room -> {
				// 방에 없는 세션이면 채팅/정답 판정을 진행하지 않는다.
				// 일반 채팅과 정답 판정을 분기해 점수/턴 종료 조건을 처리한다.
				Participant sender = resolveParticipant(room, sessionId);
				if (sender == null) {
					return RoomJob.FollowUpResult.none();
				}

				Game game = room.getGame();
				boolean drawerSealedPhase = game != null
					&& game.isPlaying()
					&& hasTurnPhase(
						game.getTurnPhase(),
						Game.TurnPhase.STARTING,
						Game.TurnPhase.WORD_CHOICE,
						Game.TurnPhase.DRAWING
					);
				boolean inDrawingPhase = game != null
					&& game.isPlaying()
					&& game.getTurnPhase() == Game.TurnPhase.DRAWING;

				if (drawerSealedPhase && sessionId.equals(game.getCurDrawerSid())) {
					broadcastSealedChat(room, game, sessionId, text);
					return RoomJob.FollowUpResult.none();
				}

				// 로비/라운드 대기/결과 화면 등 DRAWING 외 구간은 일반 채팅으로 전체 전파한다.
				if (!inDrawingPhase) {
					broadcastChatToAllExceptSender(room, sessionId, text);
					return RoomJob.FollowUpResult.none();
				}

				EndMode endMode = game.getGameSetting().endMode();
				boolean isAllCorrectMode = endMode == EndMode.TIME_OR_ALL_CORRECT;

				boolean alreadyCorrect = game.getEarnedPoints().containsKey(sessionId);
				if (alreadyCorrect) {
					if (isAllCorrectMode) {
						broadcastSealedChat(room, game, sessionId, text);
					} else {
						broadcastChatToAllExceptSender(room, sessionId, text);
					}
					return RoomJob.FollowUpResult.none();
				}

				if (isBlank(text) || isBlank(game.getAnswerWord())) {
					broadcastChatToAllExceptSender(room, sessionId, text);
					return RoomJob.FollowUpResult.none();
				}

				String normalizedText = normalizeText(text);
				String normalizedAnswer = normalizeText(game.getAnswerWord());

				// 아직 정답하지 않은 추측자의 메시지에서만 정답 여부를 판정한다.
				if (!normalizedText.equals(normalizedAnswer)) {
					broadcastChatToAllExceptSender(room, sessionId, text);
					return RoomJob.FollowUpResult.none();
				}

				// 정답 메시지는 채팅으로 방송하지 않고 이번 턴 결과용 점수 예약과 종료 판정만 처리한다.
				int guessScore = calculateGuessScore(game);

				if(!game.getEarnedPoints().containsKey(game.getCurDrawerSid())){
					int drawerPoint = (guessScore + 1) / 2;
					game.getEarnedPoints().putIfAbsent(game.getCurDrawerSid(), drawerPoint);
				}

				Integer existingPoint = game.getEarnedPoints().putIfAbsent(sessionId, guessScore);
				if (existingPoint != null) {
					return RoomJob.FollowUpResult.none();
				}
				broadcastCorrectAnswer(room, game, sessionId);

				boolean shouldEndTurn = false;
				String endReason = "CORRECT_ANSWER";
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
					turnEnd(endReason),
					null,
					null
				);
			}
		);
	}

	/**
	 * 발신자를 제외한 모든 참가자에게 일반 채팅 이벤트(204)를 전파한다.
	 */
	private void broadcastChatToAllExceptSender(Room room, String sessionId, String text) {
		// 송신자를 제외한 전체에게 일반 채팅 이벤트를 보낸다.
		for (Participant participant : room.getParticipants().values()) {
			if (!participant.sessionId().equals(sessionId)) {
				sendToParticipant(participant, 204, Map.of(
					"sid", sessionId,
					"t", text
				));
			}
		}
	}

	/**
	 * sealed 채팅을 전파한다.
	 * 수신 대상은 현재 drawer 또는 이미 정답 처리된 참가자로 제한된다.
	 * 힌트/정답 노출을 막기 위한 비공개 채팅 경로에서 사용한다.
	 */
	private void broadcastSealedChat(Room room, Game game, String sessionId, String text) {
		// drawer와 정답자에게만 보이는 비공개 채팅을 전송한다.
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

	/**
	 * 정답자를 전 참가자에게 알린다.
	 * 점수는 확정하지 않고 누가 맞췄는지만 즉시 알린다.
	 */
	private void broadcastCorrectAnswer(Room room, Game game, String sessionId) {
		Map<String, Object> payload = Map.of(
			"gid", game.getGameId(),
			"tid", game.getCurTurnId(),
			"sid", sessionId
		);
		for (Participant participant : room.getParticipants().values()) {
			sendToParticipant(participant, 210, payload);
		}
	}

	private void broadcastHintPattern(Room room, Game game) {
		if (room == null || game == null || game.getHintPattern() == null) {
			return;
		}
		Map<String, Object> payload = Map.of(
			"gid", game.getGameId(),
			"tid", game.getCurTurnId(),
			"drawerSid", game.getCurDrawerSid(),
			"hintPattern", game.getHintPattern(),
			"revealedCount", game.getHintRevealedCount(),
			"totalRevealCount", game.getHintTotalRevealCount()
		);
		for (Participant participant : room.getParticipants().values()) {
			if (participant.sessionId().equals(game.getCurDrawerSid())) {
				continue;
			}
			sendToParticipant(participant, 211, payload);
		}
	}

	/**
	 * 단어 선택 단계를 종료하고 DRAWING 단계로 전환한다.
	 * 정답 단어를 확정한 뒤 그리기 시작 이벤트(208)를 전파하고
	 * drawingTimeout 타이머를 등록하며, 기존 word-choice 타이머 취소 키를 반환한다.
	 */
	private RoomJob.FollowUpResult startDrawingPhase(
		Room room,
		Game game,
		Integer choiceIndex,
		String selectionReason
	) {
		if (!validatePlayingRound(room, RoundPhase.PLAYING)) {
			return RoomJob.FollowUpResult.none();
		}
		game = room.getGame();
		if (!hasTurnPhase(game.getTurnPhase(), TurnPhase.WORD_CHOICE)) {
			log.warn(
				"startDrawingPhase ignored because turn is not in word-choice phase. roomId={}, turnId={}, turnPhase={}",
				room.getRoomId(),
				game.getCurTurnId(),
				game.getTurnPhase()
			);
			return RoomJob.FollowUpResult.none();
		}
		List<WordEntry> wordCandidates = game.getWordCandidates();
		if (wordCandidates == null || wordCandidates.isEmpty()) {
			log.warn(
				"startDrawingPhase ignored because word candidates are empty. roomId={}, turnId={}",
				room.getRoomId(),
				game.getCurTurnId()
				);
			return RoomJob.FollowUpResult.none();
		}
		int resolvedChoiceIndex;
		if (choiceIndex == null) {
			resolvedChoiceIndex = ThreadLocalRandom.current().nextInt(wordCandidates.size());
		} else {
			resolvedChoiceIndex = choiceIndex;
		}
		if (resolvedChoiceIndex < 0 || resolvedChoiceIndex >= wordCandidates.size()) {
			log.warn(
				"startDrawingPhase ignored because choiceIndex out of range. roomId={}, turnId={}, choiceIndex={}, candidateCount={}",
				room.getRoomId(),
				game.getCurTurnId(),
				resolvedChoiceIndex,
				wordCandidates.size()
			);
			return RoomJob.FollowUpResult.none();
		}

		// 단어 직접 선택/시간초과 선택 모두 이 경로에서 DRAWING으로 전환한다.
		// 선택된 단어로 DRAWING 단계에 진입하고 공통 상태/타이머를 세팅한다.
		room.getCurrentCanvas().clear();
		game.startDrawing(resolvedChoiceIndex);
		int drawSec = normalizePositiveSeconds(game.getGameSetting().drawSec(), 40);
		game.setDeadlineAt(Instant.now().plusSeconds(drawSec));
		String hintPattern = game.getHintPattern();

		Map<String, Object> drawerPayload = Map.of(
			"gid", game.getGameId(),
			"drawSec", drawSec,
			"drawerSid", game.getCurDrawerSid(),
			"answerEntry", game.getAnswerWordEntry()
		);

		Map<String, Object> guesserPayload = Map.of(
			"gid", game.getGameId(),
			"drawSec", drawSec,
			"drawerSid", game.getCurDrawerSid(),
			"answerLength", game.getAnswerWord().length(),
			"hintPattern", hintPattern == null ? "" : hintPattern
		);

		for (Participant participant : room.getParticipants().values()) {
			if (participant.sessionId().equals(game.getCurDrawerSid()))
				sendToParticipant(participant, 208, drawerPayload);
			else
				sendToParticipant(participant, 208, guesserPayload);
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

		List<RoomJob.FollowUp> followUps = new ArrayList<>();
		followUps.add(new RoomJob.FollowUp(
			drawingTimeout(),
			Duration.ofSeconds(drawSec),
			DRAWING_TIMER_KEY
		));
		int hintRevealSec = game.getGameSetting().hintRevealSec();
		int hintLetterCount = game.getGameSetting().hintLetterCount();
		if (hintRevealSec > 0 && hintLetterCount > 0 && game.hasPendingHintReveals()) {
			followUps.add(new RoomJob.FollowUp(
				hintRevealTick(),
				Duration.ofSeconds(hintRevealSec),
				HINT_REVEAL_TIMER_KEY
			));
		}
		// 직접 선택/자동 선택 시 남아있는 단어선택 타이머를 반드시 무효화한다.
		return RoomJob.FollowUpResult.followUps(followUps, WORD_CHOICE_TIMER_KEY);
	}

	/**
	 * 요청 개수에 맞는 단어 후보 목록을 만든다.
	 * 최근 정답 단어를 우선 제외해 후보를 고르고,
	 * 부족하면 제외 조건을 완화해 추가 보충한다.
	 */
	private List<WordEntry> resolveWordChoices(Game game, int requestedCount) {
		int targetCount = requestedCount <= 0 ? 1 : requestedCount;

		Set<String> usedWords = game == null || game.getRecentAnswerWordCounts() == null
			? Set.of()
			: new HashSet<>(game.getRecentAnswerWordCounts().keySet());
		CustomWordMode customWordMode = game.getGameSetting().customWordMode();
		List<WordEntry> gameCustomPool = game.getCustomWordPool();
		List<WordEntry> freshWords = wordPoolProvider.pickRandomWords(
			targetCount,
			usedWords,
			customWordMode,
			gameCustomPool
		);

		if (freshWords.size() >= targetCount) {
			return freshWords;
		}

		List<WordEntry> resolved = new ArrayList<>(freshWords);
		Set<String> selectedWords = new HashSet<>();
		for (WordEntry wordEntry : resolved) {
			if (wordEntry == null || wordEntry.word() == null) {
				continue;
			}
			selectedWords.add(wordEntry.word());
		}
		List<WordEntry> refillWords = wordPoolProvider.pickRandomWords(
			targetCount - resolved.size(),
			selectedWords,
			customWordMode,
			gameCustomPool
		);
		resolved.addAll(refillWords);
		if (resolved.size() < targetCount) {
			List<WordEntry> duplicateFallbackWords = wordPoolProvider.pickRandomWordsAllowDuplicate(
				targetCount - resolved.size(),
				customWordMode,
				gameCustomPool
			);
			resolved.addAll(duplicateFallbackWords);
		}

		if (resolved.size() < targetCount) {
			log.warn(
				"word choices truncated due to effective pool size. roomGameId={}, requestedCount={}, resolvedCount={}, customWordMode={}",
				game == null ? null : game.getGameId(),
				targetCount,
				resolved.size(),
				customWordMode.code()
			);
		} else {
			log.info(
				"word choices resolved with exclusions and refill. roomGameId={}, requestedCount={}, freshCount={}, usedWordCount={}, customWordMode={}",
				game == null ? null : game.getGameId(),
				targetCount,
				freshWords.size(),
				usedWords.size(),
				customWordMode.code()
			);
		}
		return List.copyOf(resolved.subList(0, Math.min(targetCount, resolved.size())));
	}

	/**
	 * 초 단위 설정값을 정규화한다.
	 * 0 이하 값은 defaultValue로 대체하고, 양수면 원본 값을 그대로 반환한다.
	 */
	private int normalizePositiveSeconds(int seconds, int defaultValue) {
		// 0 이하 값은 기본값으로 치환해 타이머 입력을 안전하게 만든다.
		if (seconds <= 0) {
			return defaultValue;
		}
		return seconds;
	}

	/**
	 * drawer 이탈 강제 종료 시 turn phase에 대응하는 타이머 키를 계산한다.
	 */
	private String resolveTurnTimerCancelKey(Game.TurnPhase turnPhase) {
		if (turnPhase == Game.TurnPhase.WORD_CHOICE) {
			return WORD_CHOICE_TIMER_KEY;
		}
		if (turnPhase == Game.TurnPhase.DRAWING) {
			return DRAWING_PHASE_TIMER_CLEAR_KEY;
		}
		return null;
	}

	/**
	 * 게임 존재 여부를 검증한다.
	 */
	private boolean validateGameExists(Room room) {
		Game game = room == null ? null : room.getGame();
		if (game != null) {
			return true;
		}
		log.warn("room job ignored because game is missing. roomId={}", room == null ? null : room.getRoomId());
		return false;
	}

	private boolean validateGamePhase(Room room, GamePhase requiredGamePhase) {
		if (!validateGameExists(room)) {
			return false;
		}
		Game game = room.getGame();
		if (game.getGamePhase() == requiredGamePhase) {
			return true;
		}
		log.warn(
			"room job ignored because game phase mismatched. roomId={}, requiredGamePhase={}, currentGamePhase={}",
			room.getRoomId(),
			requiredGamePhase,
			game.getGamePhase()
		);
		return false;
	}

	private boolean validatePlayingGame(Room room) {
		return validateGamePhase(room, GamePhase.PLAYING);
	}

	private boolean validateRoundPhase(Room room, RoundPhase requiredRoundPhase) {
		if (!validateGameExists(room)) {
			return false;
		}
		Game game = room.getGame();
		if (game.getRoundPhase() == requiredRoundPhase) {
			return true;
		}
		log.warn(
			"room job ignored because round phase mismatched. roomId={}, requiredRoundPhase={}, currentRoundPhase={}",
			room.getRoomId(),
			requiredRoundPhase,
			game.getRoundPhase()
		);
		return false;
	}

	private boolean validatePlayingRound(Room room, RoundPhase requiredRoundPhase) {
		return validatePlayingGame(room)
			&& validateRoundPhase(room, requiredRoundPhase);
	}

	private boolean validateTurnPhase(Room room, TurnPhase... allowedTurnPhases) {
		if (!validateGameExists(room)) {
			return false;
		}
		Game game = room.getGame();
		if (hasTurnPhase(game.getTurnPhase(), allowedTurnPhases)) {
			return true;
		}
		log.warn(
			"room job ignored because turn phase mismatched. roomId={}, currentTurnPhase={}",
			room.getRoomId(),
			game.getTurnPhase()
		);
		return false;
	}

	private boolean validatePlayingTurn(Room room, RoundPhase requiredRoundPhase, TurnPhase... allowedTurnPhases) {
		return validatePlayingRound(room, requiredRoundPhase)
			&& validateTurnPhase(room, allowedTurnPhases);
	}

	private boolean hasTurnPhase(TurnPhase currentTurnPhase, TurnPhase... allowedTurnPhases) {
		if (currentTurnPhase == null || allowedTurnPhases == null || allowedTurnPhases.length == 0) {
			return false;
		}
		for (TurnPhase allowedTurnPhase : allowedTurnPhases) {
			if (currentTurnPhase == allowedTurnPhase) {
				return true;
			}
		}
		return false;
	}

	/**
	 * sessionId로 참가자를 조회한다.
	 * room 또는 sessionId가 비정상이면 null을 반환한다.
	 */
	private Participant resolveParticipant(Room room, String sessionId) {
		// room/sessionId 유효성을 확인한 뒤 참가자를 조회한다.
		if (room == null || isBlank(sessionId)) {
			return null;
		}
		return room.getParticipants().get(sessionId);
	}

	/**
	 * 요청 참가자가 방장인지 검증한다.
	 * 방장이 아니면 FORBIDDEN 에러를 전송하고 true(거절) 반환,
	 * 방장이면 false 반환한다.
	 */
	private boolean rejectIfNotHost(Room room, Participant participant, String message) {
		// 요청자가 방장이 아니면 에러를 보내고 거절한다.
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

	/**
	 * 요청 참가자가 현재 턴의 drawer인지 검증한다.
	 * drawer가 아니면 FORBIDDEN 에러를 전송하고 true(거절) 반환한다.
	 */
	private boolean rejectIfNotCurrentDrawer(Game game, Participant participant, String message) {
		// 요청자가 현재 턴의 drawer가 아니면 에러를 보내고 거절한다.
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

	private boolean rejectIfGameAlreadyExists(Room room, Participant participant, String reason, String message) {
		if (room == null || room.getGame() == null) {
			return false;
		}
		if (participant != null) {
			sendErrorToParticipant(
				participant,
				1999,
				reason,
				message
			);
		}
		return true;
	}

	/**
	 * 현재 턴에서 drawer를 제외한 모든 참가자가 정답했는지 계산한다.
	 * EndMode.TIME_OR_ALL_CORRECT 판정에 사용된다.
	 */
	private boolean allGuessersSolved(Room room, Game game) {
		// drawer를 제외한 모든 참가자가 정답 처리됐는지 계산한다.
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

	/**
	 * 현재 DRAWING 남은 시간을 기반으로 정답자 점수를 계산한다.
	 * 현재 턴 drawSec을 100%로 보고 남은 시간 비율만큼 최대 20점을 부여한다.
	 */
	private int calculateGuessScore(Game game) {
		if (game == null || game.getDeadlineAt() == null || game.getGameSetting() == null) {
			return 1;
		}
		int drawSec = normalizePositiveSeconds(game.getGameSetting().drawSec(), 40);
		long totalDrawMs = drawSec * 1000L;
		long remainingMs = Duration.between(Instant.now(), game.getDeadlineAt()).toMillis();
		if (remainingMs < 0) {
			remainingMs = 0;
		}
		if (totalDrawMs <= 0) {
			return 1;
		}
		double rawScore = (remainingMs * 20.0) / totalDrawMs;
		int scaledScore = (int) Math.ceil(rawScore);
		return Math.max(1, Math.min(20, scaledScore));
	}

	// /**
	//  * 이번 턴에 정답자가 한 명 이상 있었으면 drawer 보너스를 부여한다.
	//  * drawer 보너스는 고정 10점이다.
	//  * 이 점수 역시 턴 결과 공개 전까지 earnedPoints에만 보관한다.
	//  */
	// private void applyDrawerBonus(Game game) {
	// 	if (game == null
	// 		|| isBlank(game.getCurDrawerSid())
	// 		|| game.getEarnedPoints() == null
	// 		|| game.getEarnedPoints().isEmpty()) {
	// 		return;
	// 	}

	// 	int drawerBonusScore = 10;
	// 	Integer existingScore = game.getEarnedPoints().putIfAbsent(game.getCurDrawerSid(), drawerBonusScore);
	// 	if (existingScore != null) {
	// 		return;
	// 	}
	// }

	/**
	 * earnedPoints에 쌓아둔 이번 턴 점수를 totalPoints에 반영한다.
	 * 점수판 누적은 턴 결과가 시작될 때 한 번만 수행한다.
	 */
	private void applyEarnedPointsToTotalPoints(Game game) {
		if (game == null || game.getEarnedPoints() == null || game.getEarnedPoints().isEmpty()) {
			return;
		}
		for (Map.Entry<String, Integer> entry : game.getEarnedPoints().entrySet()) {
			String sessionId = entry.getKey();
			Integer score = entry.getValue();
			if (isBlank(sessionId) || score == null || score <= 0) {
				continue;
			}
			game.getTotalPoints().merge(sessionId, score, Integer::sum);
		}
	}

	/**
	 * null 또는 공백 문자열인지 판별한다.
	 */
	private boolean isBlank(String value) {
		// null 또는 공백 문자열 여부를 확인한다.
		return value == null || value.isBlank();
	}

	/**
	 * 정답 비교를 위한 텍스트 정규화를 수행한다.
	 * trim + 소문자 변환으로 비교 노이즈를 줄인다.
	 */
	private String normalizeText(String value) {
		// 정답 비교용으로 trim + 소문자 정규화를 수행한다.
		if (value == null) {
			return "";
		}
		return value.trim().toLowerCase(Locale.ROOT);
	}

	/**
	 * 참가자 목록을 입장 시각 기준으로 정렬해 반환한다.
	 * 동률일 때는 sessionId 오름차순으로 안정 정렬한다.
	 */
	private List<Participant> resolveParticipantsInJoinOrder(Room room) {
		// 참가자를 입장 시각 기준으로 정렬해 게임 기본 순서를 만든다.
		return room.getParticipants()
			.values()
			.stream()
			.sorted(
				Comparator
					.comparing((Participant participant) -> parseJoinedAtOrMax(participant.joinedAt()))
					.thenComparing(Participant::sessionId)
			)
			.toList();
	}

	/**
	 * 라운드 drawer 순서를 생성한다.
	 * 기본은 입장순이며, 설정이 RANDOM이면 셔플한 순서를 반환한다.
	 */
	private List<String> resolveRoundDrawerSids(Room room) {
		// 라운드 drawer 순서를 만들고 RANDOM 설정이면 셔플한다.
		List<Participant> participants = new ArrayList<>(resolveParticipantsInJoinOrder(room));
		if (room.getSetting().drawerOrderMode() == DrawerOrderMode.RANDOM) {
			Collections.shuffle(participants);
		}
		return participants.stream().filter(Objects::nonNull).map(Participant::sessionId).toList();
	}

	/**
	 * 다음 방장을 선택한다.
	 * 현재 참가자 중 가장 먼저 입장한 사용자를 우선한다.
	 */
	private Participant selectNextHostParticipant(Map<String, Participant> participants) {
		// 가장 먼저 입장한 참가자를 다음 방장으로 선택한다.
		return participants.values()
			.stream()
			.min(
				Comparator
					.comparing((Participant participant) -> parseJoinedAtOrMax(participant.joinedAt()))
					.thenComparing(Participant::sessionId)
			)
			.orElse(null);
	}

	/**
	 * 현재 방 참가자들의 색상 사용 현황을 기준으로 다음 colorIndex를 선택한다.
	 * 사용되지 않은 색이 있으면 그중 랜덤으로, 모두 사용 중이면 최소 사용 색 중 랜덤으로 고른다.
	 */
	private int resolveNextColorIndex(Map<String, Participant> participants) {
		int[] usageCounts = new int[MAX_COLOR_INDEX + 1];
		if (participants != null && !participants.isEmpty()) {
			for (Participant participant : participants.values()) {
				if (participant == null) {
					continue;
				}
				int colorIndex = participant.colorIndex();
				if (colorIndex < MIN_COLOR_INDEX || colorIndex > MAX_COLOR_INDEX) {
					continue;
				}
				usageCounts[colorIndex] += 1;
			}
		}

		List<Integer> candidateColorIndexes = new ArrayList<>();
		for (int colorIndex = MIN_COLOR_INDEX; colorIndex <= MAX_COLOR_INDEX; colorIndex += 1) {
			if (usageCounts[colorIndex] == 0) {
				candidateColorIndexes.add(colorIndex);
			}
		}
		if (!candidateColorIndexes.isEmpty()) {
			return candidateColorIndexes.get(ThreadLocalRandom.current().nextInt(candidateColorIndexes.size()));
		}

		int minUsageCount = Integer.MAX_VALUE;
		for (int colorIndex = MIN_COLOR_INDEX; colorIndex <= MAX_COLOR_INDEX; colorIndex += 1) {
			minUsageCount = Math.min(minUsageCount, usageCounts[colorIndex]);
		}
		for (int colorIndex = MIN_COLOR_INDEX; colorIndex <= MAX_COLOR_INDEX; colorIndex += 1) {
			if (usageCounts[colorIndex] == minUsageCount) {
				candidateColorIndexes.add(colorIndex);
			}
		}
		return candidateColorIndexes.get(ThreadLocalRandom.current().nextInt(candidateColorIndexes.size()));
	}

	/**
	 * joinedAt 문자열을 Instant로 파싱한다.
	 * 파싱 실패 시 정렬 우선순위를 뒤로 보내기 위해 Instant.MAX를 반환한다.
	 */
	private Instant parseJoinedAtOrMax(String joinedAt) {
		// joinedAt 파싱 실패 시 정렬에서 뒤로 가도록 Instant.MAX를 사용한다.
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

	/**
	 * 단일 참가자에게 아웃바운드 이벤트를 발행한다.
	 * payload가 JsonNode가 아니면 mapper로 변환 후 envelope(eventCode)로 감싸 전송한다.
	 */
	private void sendToParticipant(Participant participant, int eventCode, Object payload) {
		// payload를 JsonNode로 변환해 단일 참가자에게 이벤트를 발행한다.
		JsonNode payloadNode = payload instanceof JsonNode jsonNode
			? jsonNode
			: commonMapper.rawMapper().valueToTree(payload);

		geEventPublisher.publish(
				participant.wsNodeId(),
				new GeEvent(
						participant.sessionId(),
						new KopicEnvelope(eventCode, payloadNode),
						TimeFormatUtil.now()));
	}

	/**
	 * 단일 참가자에게 에러 이벤트를 전송한다.
	 * reason/message를 표준 에러 payload로 구성해 지정된 errorEventCode로 발행한다.
	 */
	private void sendErrorToParticipant(Participant participant, int errorEventCode, String reason, String message) {
		// reason/message 구조의 에러 이벤트를 단일 참가자에게 발행한다.
		geMetrics.increment(
			"kopic_ge_inbound_rejected_total",
			"reason",
			reason
		);
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

	private void sendErrorToSession(String wsNodeId, String sessionId, int errorEventCode, String reason,
		String message) {
		sendErrorToParticipant(
			new Participant(wsNodeId, sessionId, null, MIN_COLOR_INDEX, TimeFormatUtil.now()),
			errorEventCode,
			reason,
			message
		);
	}

	private String metricRoomType(int roomType) {
		if (roomType == Room.QUICK_ROOM_TYPE) {
			return "quick";
		}
		if (roomType == Room.PRIVATE_ROOM_TYPE) {
			return "private";
		}
		return "other";
	}


	@Override
	public RoomJob updateSetting(String requestedSessionId, JsonNode settingPayload) {
		return new RoomJob(
			room -> {
				// 방장 요청만 허용해 설정을 갱신하고 다른 참가자에게 변경 이벤트를 전파한다.
				Participant requestedParticipant = resolveParticipant(room, requestedSessionId);
				if (requestedParticipant == null) {
					return RoomJob.FollowUpResult.none();
				}
				if (room.getRoomType() == Room.QUICK_ROOM_TYPE) {
					sendErrorToParticipant(
						requestedParticipant,
						1999,
						"INVALID_REQUEST",
						"update setting is not allowed in quick room"
					);
					return RoomJob.FollowUpResult.none();
				}

				if (rejectIfNotHost(room, requestedParticipant, "only host can update game setting")) {
					return RoomJob.FollowUpResult.none();
				}

				if (rejectIfGameAlreadyExists(room, requestedParticipant, "INVALID_REQUEST", "game already started")) {
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
					if (!participant.sessionId().equals(requestedSessionId)) {
						sendToParticipant(participant, 107, settingPayload);
					}
				}
				return RoomJob.FollowUpResult.none();
			}
		);
	}

	

}
