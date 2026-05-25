package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.error.ErrorCode;
import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import io.jhpark.kopic.ge.room.registry.RoomSessionStore;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRoomService implements RoomService {

	private static final int PRIVATE_ROOM_CODE_MAX_RETRY = 20;

	private final RoomSessionStore sessionStore;
	private final RoomRunner roomRunner;
	private final RoomJobFactory roomJobFactory;

	@Override
	public synchronized Room bootstrapRoom(
		int roomType,
		String hostSessionId
	) {
		if (roomType == Room.PRIVATE_ROOM_TYPE) {
			return bootstrapPrivateRoom(hostSessionId);
		}

		Room room = new Room(roomType, hostSessionId);
		return putRoom(room);
	}

	private Room bootstrapPrivateRoom(String hostSessionId) {
		for (int retry = 0; retry < PRIVATE_ROOM_CODE_MAX_RETRY; retry++) {
			Room room = new Room(Room.PRIVATE_ROOM_TYPE, hostSessionId);
			if (!isGeneratedPrivateRoomAvailable(room)) {
				continue;
			}
			try {
				return putRoom(room);
			} catch (IllegalStateException illegalStateException) {
				log.debug(
					"private room bootstrap retrying because roomCode indexing failed. roomId={}, roomCode={}, retry={}",
					room.getRoomId(),
					room.getRoomCode(),
					retry + 1
				);
				log.debug(
					"private room bootstrap failure detail. roomId={}, roomCode={}",
					room.getRoomId(),
					room.getRoomCode(),
					illegalStateException
				);
			}
		}
		throw new IllegalStateException("failed to allocate unique private roomCode");
	}

	private Room putRoom(Room room) {
		sessionStore.put(new RoomSession(room));
		log.info(
			"room actor bootstrapped. roomId={}, roomCode={}, roomType={}, capacity={}",
			room.getRoomId(),
			room.getRoomCode(),
			room.getRoomType(),
			room.getCapacity()
		);
		return room;
	}

	@Override
	public Optional<RoomSnapshot> findRoom(String roomId) {
		return sessionStore.find(roomId)
			.map(RoomSession::getRoom)
			.map(RoomSnapshot::from);
	}

	@Override
	public RoomSubmitResult createPrivateRoom(String sessionId, String nickname, String wsNodeId) {
		Room room;
		try {
			room = bootstrapRoom(Room.PRIVATE_ROOM_TYPE, sessionId);
		} catch (IllegalStateException illegalStateException) {
			log.warn("private room creation rejected because roomCode allocation failed. sessionId={}, nickname={}",
				sessionId,
				nickname,
				illegalStateException);
			return RoomSubmitResult.rejected(
				ErrorCode.CONFLICT,
				"방을 생성할 수 없습니다. 잠시 후 다시 시도해주세요."
			);
		}
		log.debug(
			"private room created. roomId={}, roomCode={}, sessionId={}, nickname={}",
			room.getRoomId(),
			room.getRoomCode(),
			sessionId,
			nickname
		);
		return submit(room.getRoomId(), roomJobFactory.join(sessionId, nickname, wsNodeId));
	}

	@Override
	public RoomSubmitResult privateJoin(String roomCode, String sessionId, String nickname, String wsNodeId) {
		if (isBlank(roomCode)) {
			return RoomSubmitResult.rejected(
				ErrorCode.INVALID_REQUEST,
				"방 코드를 입력해주세요."
			);
		}
		Optional<String> roomIdByCode = sessionStore.findRoomIdByPrivateCode(roomCode);
		if (roomIdByCode.isEmpty()) {
			log.warn("private join rejected because roomCode was not found. roomCode={}, sessionId={}", roomCode, sessionId);
			return RoomSubmitResult.rejected(
				ErrorCode.ROOM_NOT_FOUND,
				"방을 찾을 수 없습니다. 방 코드를 확인해주세요: " + roomCode
			);
		}
		String roomId = roomIdByCode.get();
		return submit(roomId, roomJobFactory.join(sessionId, nickname, wsNodeId));
	}

	@Override
	public RoomSubmitResult quickJoin(String sessionId, String nickname, String wsNodeId) {
		Optional<String> candidateRoomId = sessionStore.findFirstAvailableQuickRoomId();
		String roomId;
		if (candidateRoomId.isPresent()) {
			roomId = candidateRoomId.get();
			log.debug(
				"quick join room selected. roomId={}, sessionId={}, nickname={}, source=existing-candidate",
				roomId,
				sessionId,
				nickname
			);
		} else {
			roomId = bootstrapRoom(Room.QUICK_ROOM_TYPE, null).getRoomId();
			log.debug(
				"quick join room created. roomId={}, sessionId={}, nickname={}, source=new-room-created",
				roomId,
				sessionId,
				nickname
			);
		}
		return submit(roomId, roomJobFactory.join(sessionId, nickname, wsNodeId));
	}

	@Override
	public RoomSubmitResult leave(String roomId, String sessionId, String wsNodeId) {
		log.debug("leave requested. roomId={}, sessionId={}", roomId, sessionId);
		return submit(roomId, roomJobFactory.leave(sessionId));
	}

	private RoomSubmitResult submit(String roomId, RoomJob job) {
		return roomRunner.submit(roomId, job);
	}

	@Override
	public RoomSubmitResult drawStroke(String roomId, String sessionId, JsonNode stroke) {
		return submit(roomId, roomJobFactory.drawStroke(sessionId, stroke));
	}

	@Override
	public RoomSubmitResult guessChat(String roomId, String sessionId, String text) {
		return submit(roomId, roomJobFactory.guessChat(sessionId, text));
	}

	@Override
	public RoomSubmitResult explicitWordChoice(String roomId, String sessionId, int choiceIndex) {
		return submit(roomId, roomJobFactory.explicitWordChoice(sessionId, choiceIndex));
	}

	private boolean isGeneratedPrivateRoomAvailable(Room room) {
		return sessionStore.findRoomIdByPrivateCode(room.getRoomCode()).isEmpty()
			&& sessionStore.find(room.getRoomId()).isEmpty();
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}


	@Override
	public RoomSubmitResult updateSetting(String roomId, String sessionId, JsonNode settingPayload) {
		return submit(roomId, roomJobFactory.updateSetting(sessionId, settingPayload));
	}

	@Override
	public RoomSubmitResult startGame(String roomId, String sessionId) {
		return submit(roomId, roomJobFactory.startGame(sessionId));	
	}

}
