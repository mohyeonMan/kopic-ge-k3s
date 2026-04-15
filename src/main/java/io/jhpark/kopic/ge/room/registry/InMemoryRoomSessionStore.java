package io.jhpark.kopic.ge.room.registry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import org.springframework.stereotype.Component;

@Component
public class InMemoryRoomSessionStore implements RoomSessionStore {

	private final Map<String, RoomSession> sessions = new ConcurrentHashMap<>();

	@Override
	public Optional<RoomSession> find(String roomId) {
		return Optional.ofNullable(sessions.get(roomId));
	}

	@Override
	public void put(RoomSession session) {
		sessions.put(session.roomId(), session);
	}

	@Override
	public void remove(String roomId) {
		sessions.remove(roomId);
	}
}
