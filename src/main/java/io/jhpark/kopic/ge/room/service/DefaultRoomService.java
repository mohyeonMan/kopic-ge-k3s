package io.jhpark.kopic.ge.room.service;

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
		Room room = newRoom(roomType, hostSessionId);
		sessionStore.put(new RoomSession(room));
		log.info(
			"room actor bootstrapped. roomId={}, roomCode={}, roomType={}, capacity={}",
			room.getRoomId(),
			room.getRoomCode(),
			roomType,
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
		Room room = bootstrapRoom(Room.PRIVATE_ROOM_TYPE, sessionId);
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
				RoomSubmitResult.Reason.INVALID_REQUEST,
				"roomCode is required"
			);
		}
		Optional<String> roomIdByCode = sessionStore.findRoomIdByPrivateCode(roomCode);
		if (roomIdByCode.isEmpty()) {
			log.warn("private join rejected because roomCode was not found. roomCode={}, sessionId={}", roomCode, sessionId);
			return RoomSubmitResult.rejected(
				RoomSubmitResult.Reason.ROOM_NOT_FOUND,
				"room not found by roomCode: " + roomCode
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

	@Override
	public RoomSubmitResult snapshot(String roomId, String sessionId, String wsNodeId) {
		return submit(roomId, roomJobFactory.snapshot(sessionId));
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

	private Room newRoom(int roomType, String hostSessionId) {
		if (roomType != Room.PRIVATE_ROOM_TYPE) {
			return new Room(roomType, hostSessionId);
		}

		for (int retry = 0; retry < PRIVATE_ROOM_CODE_MAX_RETRY; retry++) {
			Room room = new Room(roomType, hostSessionId);
			String roomCode = room.getRoomCode();
			if (sessionStore.findRoomIdByPrivateCode(roomCode).isEmpty()) {
				return room;
			}
		}
		throw new IllegalStateException("failed to allocate unique private roomCode");
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
