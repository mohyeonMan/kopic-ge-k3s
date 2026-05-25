package io.jhpark.kopic.ge.room.registry;

import io.jhpark.kopic.ge.room.dto.RoomSession;
import java.util.Optional;

public interface RoomSessionStore {

	Optional<RoomSession> find(String roomId);

	Optional<String> findRoomIdByPrivateCode(String roomCode);

	void put(RoomSession session);

	void remove(String roomId);

	boolean remove(String roomId, RoomSession expected);

	int countActiveRoomsByType(int roomType);

	int countParticipantsByType(int roomType);
}
