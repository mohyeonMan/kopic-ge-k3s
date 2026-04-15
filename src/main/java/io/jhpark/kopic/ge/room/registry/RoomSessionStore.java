package io.jhpark.kopic.ge.room.registry;

import java.util.Optional;
import io.jhpark.kopic.ge.room.dto.RoomSession;

public interface RoomSessionStore {

	Optional<RoomSession> find(String roomId);

	void put(RoomSession session);

	void remove(String roomId);
}
