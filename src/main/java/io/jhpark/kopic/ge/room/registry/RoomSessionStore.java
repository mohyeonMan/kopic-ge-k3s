package io.jhpark.kopic.ge.room.registry;

import io.jhpark.kopic.ge.room.dto.RoomSession;
import java.util.List;
import java.util.Optional;

public interface RoomSessionStore {

	Optional<RoomSession> find(String roomId);

	Optional<String> findRoomIdByPrivateCode(String roomCode);

	void put(RoomSession session);

	void remove(String roomId);

	boolean remove(String roomId, RoomSession expected);

	List<String> roomIds();

	int countActiveRoomsByType(int roomType);

	int countParticipantsByType(int roomType);
}
