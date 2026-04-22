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
import io.jhpark.kopic.ge.room.dto.Game.GamePhase;
import io.jhpark.kopic.ge.room.dto.Game.RoundPhase;
import io.jhpark.kopic.ge.room.dto.Game.TurnPhase;

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
import java.util.Objects;
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

					Participant newParticipant = new Participant(
						wsNodeId,
						sessionId,
						nickname,
						TimeFormatUtil.now()
					);
					
					Map<String, Participant> participants = room.getParticipants();
					// 정원 초과인 경우 참가자 추가 없이 에러 이벤트만 응답한다.
					if (participants.size() >= room.getCapacity()) {
						sendErrorToParticipant(newParticipant, 1999, "ROOM_FULL", "room is full");
						return RoomJob.FollowUpResult.none();
					}

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
								"nickname", nickname));
					}

					log.info("current room participants: {}", room.getParticipants().keySet());

					// 입장으로 인해 비어있지 않게 되었으므로 close-if-empty 타이머는 취소 대상으로 반환한다.
					RoomJob.FollowUp followUp = null;
					boolean shouldAutoStartQuickGame =
						room.getRoomType() == Room.QUICK_ROOM_TYPE
							&& room.getGame() == null
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
					String cancelTimerKey = null;
					if (game != null) {
						// stale game 상태를 남기지 않도록 게임/타이머를 같이 정리한다.
						cancelTimerKey = resolveGameTimerCancelKeyForLeave(game);
						game.removeParticipant(sessionId);
						room.endGame();
					}
					room.transferHost(null);
					room.getCurrentCanvas().clear();
					log.info(
						"room became empty after leave. roomId={}, sessionId={}, closeDelaySeconds={}",
						room.getRoomId(),
						sessionId,
						CLOSE_IF_EMPTY_DELAY.toSeconds()
					);
					return new RoomJob.FollowUpResult(
						new RoomJob.FollowUp(
							closeIfEmpty(),
							CLOSE_IF_EMPTY_DELAY,
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
				if (game != null) {
					boolean wasCurrentDrawer = sessionId.equals(game.getCurDrawerSid());
					Game.TurnPhase turnPhase = game.getTurnPhase();
					String currentTurnId = game.getCurTurnId();
					game.removeParticipant(sessionId);

					// 게임은 2명 이상에서만 유지한다.
					if (participants.size() < 2) {
						cancelTimerKey = resolveGameTimerCancelKeyForLeave(game);
						room.endGame();
						room.getCurrentCanvas().clear();
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
							turnEnd(currentTurnId, "DRAWER_LEFT"),
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
				}

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
	@Override
	public RoomJob closeIfEmpty() {
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

				
				Game currentGame = room.getGame();
				if (currentGame != null && !currentGame.isGameResult()) {
					if (requestedParticipant != null) {
						sendErrorToParticipant(
							requestedParticipant,
							1999,
							"CONFLICT",
							"game is already active"
						);
					}
					return RoomJob.FollowUpResult.none();
				}

				Game newGame =room.startGame();

				Map<String, String> payload = Map.of(
					"gid", newGame.getGameId()
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
				return RoomJob.FollowUpResult.followUp(
					nextRound(),
					START_ROUND_DELAY,
					START_ROUND_TIMER_KEY
				);
			}
		);
	}

	/**
	 * 다음 라운드를 시작한다.
	 * 현재 게임이 활성 상태인지, 다음 라운드가 남아 있는지 확인한 뒤
	 * 라운드 상태/그리는 순서를 초기화하고 라운드 시작 이벤트(202)를 전파한다.
	 * 라운드 시작 직후에는 즉시 nextTurn follow-up으로 이어진다.
	 */
	@Override
	public RoomJob nextRound() {
		return new RoomJob(
			room -> {
				// 다음 라운드를 열고 라운드 시작 이벤트를 전파한 뒤 턴 준비로 이동한다.
				Game game = room.getGame();
				if (game == null || game.getGamePhase() != GamePhase.PLAYING) {
					log.info("can't start round because game is not playing");
					return RoomJob.FollowUpResult.none();
				}

				RoundPhase roundPhase = game.getRoundPhase();

				if(roundPhase != RoundPhase.READY){
					log.info("can't start round because previous round finished invalid");
					return RoomJob.FollowUpResult.none();
				}

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
					"drawerSids", game.getCurRoundDrawerSids()
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
				
				return RoomJob.FollowUpResult.followUp(
					nextTurn(),
					null,
					null
				);
			}
		);
	}

	/**
	 * 다음 턴 준비를 수행한다.
	 * 현재 라운드의 drawer 목록을 기준으로 Game.startTurn() 전이를 시도하고
	 * 성공하면 캔버스를 비운 뒤 단어 선택 창 오픈 잡(openWordChoiceWindow)으로 연결한다.
	 * 전이 불가 상태(phase 불일치, 인덱스 범위 문제)는 예외를 잡아 무시한다.
	 */
	@Override
	public RoomJob nextTurn() {
		return new RoomJob(
			room -> {
				// 턴 메타데이터를 준비하고 단어 선택 단계로 전환한다.
				Game game = room.getGame();
				if (game == null || game.getGamePhase() != GamePhase.PLAYING ) {
					log.info("can't start round");
					return RoomJob.FollowUpResult.none();
				}

				RoundPhase roundPhase = game.getRoundPhase();

				if (roundPhase == null || roundPhase != RoundPhase.PLAYING) {
					log.info("can't start round");
					return RoomJob.FollowUpResult.none();
				}

				if(game.getTurnPhase() != TurnPhase.READY){
					log.info("can't start round");
					return RoomJob.FollowUpResult.none();
				}

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
				return RoomJob.FollowUpResult.followUp(
					openWordChoiceWindow(game.getCurTurnId()),
					null,
					null
				);
			}
		);
	}

	/**
	 * 단어 선택 창을 연다.
	 * expectedTurnId로 오래된 타이머 실행을 차단하고, 현재 턴 상태가 단어 선택 가능인지 검증한다.
	 * 후보 단어는 drawer에게만 포함해 이벤트(203)로 전파하며
	 * wordChoiceTimeout 타이머 follow-up을 등록한다.
	 */
	@Override
	public RoomJob openWordChoiceWindow(String expectedTurnId) {
		return new RoomJob(
			room -> {

				Game game = room.getGame();
				if (game == null || game.getGamePhase() != GamePhase.PLAYING ) {
					log.info("can't start round");
					return RoomJob.FollowUpResult.none();
				}

				RoundPhase roundPhase = game.getRoundPhase();

				if (roundPhase == null || roundPhase != RoundPhase.PLAYING) {
					log.info("can't start round");
					return RoomJob.FollowUpResult.none();
				}

				if(game.getTurnPhase() != TurnPhase.STARTING){
					log.info("can't start round");
					return RoomJob.FollowUpResult.none();
				}

				// 단어 후보는 현재 룸 설정값을 기준으로 턴마다 새로 만든다.
				List<String> words = resolveWordChoices(game.getGameSetting().wordChoiceCount());
				game.openWordCandidate(words);
				int choiceSec = normalizePositiveSeconds(game.getGameSetting().wordChoiceSec(), 10);

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
					wordChoiceTimeout(game.getCurTurnId()),
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
					log.info("can't start round");
					return RoomJob.FollowUpResult.none();
				}

				Game game = room.getGame();
				if (game == null || game.getGamePhase() != GamePhase.PLAYING ) {
					log.info("can't start round");
					return RoomJob.FollowUpResult.none();
				}

				RoundPhase roundPhase = game.getRoundPhase();

				if (roundPhase == null || roundPhase != RoundPhase.PLAYING) {
					log.info("can't start round");
					return RoomJob.FollowUpResult.none();
				}

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
	 * 현재 턴이 WORD_CHOICE 상태인지와 turnId 일치 여부를 확인한 뒤
	 * 후보 단어 중 하나를 자동 선택하여 DRAWING 단계로 전환한다.
	 */
	@Override
	public RoomJob wordChoiceTimeout(String expectedTurnId) {
		return new RoomJob(
			room -> {
				// 단어 선택 시간이 지나면 후보 중 하나를 자동 선택한다.
				Game game = resolveActivePlayingGame(room);
				if (game == null) {
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
				return startDrawingPhase(room, game, null, "TIMEOUT_AUTO_PICK");
			}
		);
	}

	/**
	 * 그리기 시간 만료를 처리한다.
	 * turnId/phase를 검증해 오래된 타이머 실행을 무시하고
	 * 유효한 경우 turnEnd("DRAWING_TIMEOUT") follow-up으로 턴 종료를 유도한다.
	 */
	@Override
	public RoomJob drawingTimeout(String expectedTurnId) {
		return new RoomJob(
			room -> {
				// 드로잉 시간이 종료되면 현재 턴을 결과 단계로 마무리한다.
				Game game = resolveActivePlayingGame(room);
				if (game == null) {
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

	/**
	 * 턴 종료를 처리한다.
	 * 턴 결과 phase로 전이한 뒤 결과 이벤트(205)를 전파하고
	 * 남은 진행 상태에 따라 nextTurn / nextRound / gameEnd 중 다음 잡을 결정한다.
	 * 결과 화면 유지 시간(TURN_RESULT_DELAY) 후속 실행과 drawing 타이머 취소를 함께 반환한다.
	 */
	@Override
	public RoomJob turnEnd(String expectedTurnId, String endReason) {
		return new RoomJob(
			room -> {
				// 턴 결과를 확정/전파하고 다음 액션(nextTurn/nextRound/gameEnd)을 결정한다.
				Game game = resolveActivePlayingGame(room);
				if (game == null) {
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

				RoomJob nextJob;
				String nextAction;
				if (game.hasNextTurn()) {
					nextJob = nextTurn();
					nextAction = "nextTurn";
				} else if (game.hasNextRound()) {
					game.finishRoundResult();
					nextJob = nextRound();
					nextAction = "nextRound";
				} else {
					game.finishRoundResult();
					nextJob = gameEnd();
					nextAction = "gameEnd";
				}
				log.info(
					"turn ended. roomId={}, gameId={}, roundNo={}, turnId={}, endReason={}, nextAction={}, resultDelaySec={}",
					room.getRoomId(),
					game.getGameId(),
					game.getCurRoundIndex(),
					game.getCurTurnId(),
					endReason,
					nextAction,
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

	/**
	 * 라운드 종료 처리 확장 포인트.
	 * 현재 구현은 비어 있으며 notImplemented 경로로만 동작한다.
	 */
	@Override
	public RoomJob roundEnd(int expectedRoundNo) {
		// 아직 미구현 상태이므로 공통 no-op 핸들러로 위임한다.
		return notImplemented("roundEnd");
	}

	/**
	 * 게임 종료 처리 확장 포인트.
	 * 현재 구현은 비어 있으며 notImplemented 경로로만 동작한다.
	 */
	@Override
	public RoomJob gameEnd() {
		// 아직 미구현 상태이므로 공통 no-op 핸들러로 위임한다.
		return notImplemented("gameEnd");
	}

	/**
	 * 결과 화면 종료 처리 확장 포인트.
	 * 현재 구현은 비어 있으며 notImplemented 경로로만 동작한다.
	 */
	@Override
	public RoomJob resultViewEnd() {
		// 아직 미구현 상태이므로 공통 no-op 핸들러로 위임한다.
		return notImplemented("resultViewEnd");
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
	 * DRAWING 구간에서는 정답 판정, 점수 반영, 턴 종료 조건(첫 정답/전원 정답)을 함께 처리한다.
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
	 * 단어 선택 단계를 종료하고 DRAWING 단계로 전환한다.
	 * 정답 단어를 확정한 뒤 턴 상태 이벤트(203)를 전파하고
	 * drawingTimeout 타이머를 등록하며, 기존 word-choice 타이머 취소 키를 반환한다.
	 */
	private RoomJob.FollowUpResult startDrawingPhase(
		Room room,
		Game game,
		Integer choiceIndex,
		String selectionReason
	) {
		if (room == null || game == null) {
			return RoomJob.FollowUpResult.none();
		}
		if (game.getGamePhase() != GamePhase.PLAYING || game.getRoundPhase() != RoundPhase.PLAYING) {
			return RoomJob.FollowUpResult.none();
		}
		if (game.getTurnPhase() != Game.TurnPhase.WORD_CHOICE) {
			log.warn(
				"startDrawingPhase ignored because turn is not in word-choice phase. roomId={}, turnId={}, turnPhase={}",
				room.getRoomId(),
				game.getCurTurnId(),
				game.getTurnPhase()
			);
			return RoomJob.FollowUpResult.none();
		}
		List<String> wordCandidates = game.getWordCandidates();
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

	/**
	 * 요청 개수에 맞는 단어 후보 목록을 만든다.
	 * 기본 단어 풀을 섞어 상위 N개를 사용하며, 요청 개수가 풀 크기보다 크면 순환 채움한다.
	 */
	private List<String> resolveWordChoices(int requestedCount) {
		// 외부 사전 공급자가 붙기 전까지 임시 단어 풀을 사용한다.
		// 요청 개수만큼 후보 단어를 섞어서 반환한다.
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
	 * leave 처리 중 게임 상태에 맞는 취소 타이머 키를 계산한다.
	 * 아직 라운드 진입 전이라 turnPhase가 비어 있으면 start-round 타이머를 취소 대상으로 본다.
	 */
	private String resolveGameTimerCancelKeyForLeave(Game game) {
		if (game == null) {
			return null;
		}
		Game.TurnPhase turnPhase = game.getTurnPhase();
		if (turnPhase == null) {
			return START_ROUND_TIMER_KEY;
		}
		if (turnPhase == Game.TurnPhase.WORD_CHOICE) {
			return WORD_CHOICE_TIMER_KEY;
		}
		if (turnPhase == Game.TurnPhase.DRAWING) {
			return DRAWING_TIMER_KEY;
		}
		if (turnPhase == Game.TurnPhase.TURN_RESULT) {
			return TURN_RESULT_TIMER_KEY;
		}
		return null;
	}

	/**
	 * drawer 이탈 강제 종료 시 turn phase에 대응하는 타이머 키를 계산한다.
	 */
	private String resolveTurnTimerCancelKey(Game.TurnPhase turnPhase) {
		if (turnPhase == Game.TurnPhase.WORD_CHOICE) {
			return WORD_CHOICE_TIMER_KEY;
		}
		if (turnPhase == Game.TurnPhase.DRAWING) {
			return DRAWING_TIMER_KEY;
		}
		return null;
	}

	/**
	 * 현재 방의 활성 게임을 조회한다.
	 * 게임이 없거나 PLAYING 상태가 아니면 경고 로그를 남기고 null을 반환한다.
	 */
	private Game resolveActivePlayingGame(Room room) {
		// 방의 게임이 PLAYING 상태인지 확인하고 아니면 null을 반환한다.
		Game game = room.getGame();
		if (game == null || !game.isPlaying()) {
			log.warn("room job ignored because game is not active. roomId={}", room.getRoomId());
			return null;
		}
		return game;
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
	 * 미구현 잡용 기본 핸들러를 만든다.
	 * 실제 동작은 하지 않고 경고 로그만 남긴 뒤 no-op 결과를 반환한다.
	 */
	private RoomJob notImplemented(String jobName) {
		return new RoomJob(
				room -> {
					// 미구현 잡은 경고 로그만 남기고 no-op으로 종료한다.
					log.warn("room job not implemented yet. job={}, roomId={}", jobName, room.getRoomId());
					return RoomJob.FollowUpResult.none();
				});
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
						Instant.now().toString()));
	}

	/**
	 * 단일 참가자에게 에러 이벤트를 전송한다.
	 * reason/message를 표준 에러 payload로 구성해 지정된 errorEventCode로 발행한다.
	 */
	private void sendErrorToParticipant(Participant participant, int errorEventCode, String reason, String message) {
		// reason/message 구조의 에러 이벤트를 단일 참가자에게 발행한다.
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

	/**
	 * 게임 설정 변경 요청을 처리한다.
	 * 요청자가 방장인지 확인하고 payload를 Setting으로 파싱해 방 설정을 갱신한다.
	 * 성공 시 요청자를 제외한 참가자에게 설정 변경 이벤트(107)를 전파한다.
	 */
	/**
	 * 방의 모든 참가자에게 동일한 에러 이벤트를 전송한다.
	 */
	private void broadcastErrorToAll(Room room, int errorEventCode, String reason, String message) {
		if (room == null) {
			return;
		}
		for (Participant participant : room.getParticipants().values()) {
			sendErrorToParticipant(participant, errorEventCode, reason, message);
		}
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

				if (rejectIfNotHost(room, requestedParticipant, "only host can update game setting")) {
					return RoomJob.FollowUpResult.none();
				}

				if(room.getGame() != null){
					sendErrorToParticipant(
						requestedParticipant,
						1999, "INVALID_REQUEST", 
						"game already started");
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
