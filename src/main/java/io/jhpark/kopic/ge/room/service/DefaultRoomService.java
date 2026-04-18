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

	private final RoomSessionStore sessionStore;
	private final RoomRunner roomRunner;
	private final RoomJobFactory roomJobFactory;

	@Override
	public synchronized RoomSnapshot bootstrapRoom(
		int roomType,
		String hostSessionId
	) {
		
		Room room = new Room(roomType, hostSessionId);
		sessionStore.put(new RoomSession(room));
		log.info("room actor bootstrapped. roomId={}, roomType={}, capacity={}", room.getRoomId(), roomType, room.getCapacity());
		return RoomSnapshot.from(room);
	}

	@Override
	public Optional<RoomSnapshot> findRoom(String roomId) {
		return sessionStore.find(roomId)
			.map(RoomSession::getRoom)
			.map(RoomSnapshot::from);
	}

	@Override
	public RoomSubmitResult privateJoin(String roomCode, String sessionId, String nickname, String wsNodeId) {
		String roomId = roomCode;
		bootstrapRoom(Room.PRIVATE_ROOM_TYPE, sessionId);

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
			roomId = bootstrapRoom(Room.QUICK_ROOM_TYPE, null).roomId();
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

}
