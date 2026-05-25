package io.jhpark.kopic.ge.room.registry;

import java.util.Optional;

import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.RoomSession;

public interface RoomSessionStore {

	Optional<RoomSession> find(String roomId);

	Optional<String> findRoomIdByPrivateCode(String roomCode);

	void put(RoomSession session);

	void remove(String roomId);

	boolean remove(String roomId, RoomSession expected);

	Optional<String> findFirstAvailableQuickRoomId();

	void addIndexPrivateRoom(String roomId, String roomCode);

	void removeIndexPrivateRoom(String roomId, String roomCode);

	void addQuickJoinCandidate(String roomId);

	void removeQuickJoinCandidate(String roomId);

	int countActiveRoomsByType(int roomType);

	int countParticipantsByType(int roomType);
}
