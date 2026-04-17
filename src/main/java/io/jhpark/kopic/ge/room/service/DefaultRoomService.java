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
		String roomId,
		int roomType,
		String hostSessionId,
		int capacity
	) {
		Optional<RoomSnapshot> existing = findRoom(roomId);
		if (existing.isPresent()) {
			return existing.get();
		}

		Room room = new Room(roomId);
		sessionStore.put(new RoomSession(room));
		log.info("room actor bootstrapped. roomId={}", roomId);
		return RoomSnapshot.from(room);
	}

	@Override
	public Optional<RoomSnapshot> findRoom(String roomId) {
		return sessionStore.find(roomId)
			.map(RoomSession::getRoom)
			.map(RoomSnapshot::from);
	}

	@Override
	public boolean canJoin(String roomId, String sessionId) {
		return sessionStore.find(roomId)
			.map(RoomSession::getRoom)
			.map(room -> !room.getParticipants().containsKey(sessionId))
			.orElse(false);
	}

	@Override
	public RoomSubmitResult privateJoin(String roomCode, String sessionId, String nickname, String wsNodeId) {
		String roomId = "room-1";
		bootstrapRoom(roomId, 1, sessionId, 10);

		return submit(roomId, roomJobFactory.join(sessionId, nickname, wsNodeId));
	}

	@Override
	public RoomSubmitResult quickJoin(String sessionId, String nickname, String wsNodeId) {
		String roomId = "room-1";
		return submit(roomId, roomJobFactory.join(sessionId, nickname, wsNodeId));
	}

	@Override
	public RoomSubmitResult leave(String roomId, String sessionId, String wsNodeId) {
		return submit(roomId, roomJobFactory.leave(sessionId));
	}

	@Override
	public RoomSubmitResult snapshot(String roomId, String sessionId, String requestId, String wsNodeId) {
		return submit(roomId, roomJobFactory.snapshot(sessionId, requestId));
	}

	private RoomSubmitResult submit(String roomId, RoomJob job) {
		return roomRunner.submit(roomId, job);
	}

	@Override
	public void closeRoom(String roomId) {
		sessionStore.find(roomId).ifPresent(session -> {
			if (sessionStore.remove(roomId, session)) {
				session.close();
				log.info("room actor closed by service. roomId={}", roomId);
			}
		});
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
