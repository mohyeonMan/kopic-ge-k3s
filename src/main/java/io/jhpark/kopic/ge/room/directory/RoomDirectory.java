package io.jhpark.kopic.ge.room.directory;

import io.jhpark.kopic.ge.room.dto.Room;
import java.util.List;

public interface RoomDirectory {

	boolean addPrivateRoomCode(String roomId, String roomCode);

	void removePrivateRoomCode(String roomCode);

	void addQuickAvailability(Room room);

	void removeQuickAvailability(String roomId);

	void refreshLoad(List<Room> rooms);

	void refresh(List<Room> rooms);
}
