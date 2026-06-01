package io.jhpark.kopic.ge.room.registry;

import io.jhpark.kopic.ge.common.runtime.GeRuntimeState;
import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultRoomSessionStore implements RoomSessionStore {

	private final Map<String, RoomSession> sessions = new ConcurrentHashMap<>();
	private final DefaultPrivateRoomCodeStore privateRoomCodes;
	private final GeRuntimeState runtimeState;

	@Override
	public Optional<RoomSession> find(String roomId) {
		return Optional.ofNullable(sessions.get(roomId));
	}

	@Override
	public Optional<String> findRoomIdByPrivateCode(String roomCode) {
		return privateRoomCodes.findRoomId(roomCode, sessions::containsKey);
	}

	@Override
	public void put(RoomSession session) {
		Room room = session.getRoom();
		String roomId = room.getRoomId();
		sessions.put(roomId, session);
		try {
			reservePrivateRoomCodeAndIndex(room);
		} catch (RuntimeException runtimeException) {
			sessions.remove(roomId, session);
			throw runtimeException;
		}
	}

	@Override
	public void remove(String roomId) {
		RoomSession removed = sessions.remove(roomId);
		if (removed == null) {
			return;
		}
		removeIndexesAndDirectory(removed.getRoom());
	}

	@Override
	public boolean remove(String roomId, RoomSession expected) {
		boolean removed = sessions.remove(roomId, expected);
		if (removed) {
			removeIndexesAndDirectory(expected.getRoom());
		}
		return removed;
	}

	@Override
	public List<String> roomIds() {
		return List.copyOf(sessions.keySet());
	}

	@Override
	public int countActiveRoomsByType(int roomType) {
		int count = 0;
		for (RoomSession session : sessions.values()) {
			Room room = session.getRoom();
			if (room.getRoomType() == roomType) {
				count += 1;
			}
		}
		return count;
	}

	@Override
	public int countParticipantsByType(int roomType) {
		int totalParticipants = 0;
		for (RoomSession session : sessions.values()) {
			Room room = session.getRoom();
			if (room.getRoomType() == roomType) {
				totalParticipants += room.getParticipants().size();
			}
		}
		return totalParticipants;
	}

	@Scheduled(
		fixedDelayString = "${kopic.redis.reconciliation-interval:1h}",
		initialDelayString = "${kopic.redis.initial-delay:2s}"
	)
	public void reconcileDirectory() {
		List<Room> rooms = snapshotRooms();
		privateRoomCodes.refresh(rooms);
		int participantCount = countParticipants(rooms);
		runtimeState.reconcile(rooms.size(), participantCount);
		log.debug("ge runtime state reconciled. roomCount={}, participantCount={}",
			rooms.size(), participantCount);
	}

	private List<Room> snapshotRooms() {
		ArrayList<Room> rooms = new ArrayList<>();
		for (RoomSession session : sessions.values()) {
			rooms.add(session.getRoom());
		}
		return List.copyOf(rooms);
	}

	private int countParticipants(List<Room> rooms) {
		int totalParticipants = 0;
		for (Room room : rooms) {
			totalParticipants += room.getParticipants().size();
		}
		return totalParticipants;
	}

	private void removeIndexesAndDirectory(Room room) {
		if (room.getRoomType() == Room.PRIVATE_ROOM_TYPE) {
			privateRoomCodes.remove(room.getRoomId(), room.getRoomCode());
		}
	}

	private void reservePrivateRoomCodeAndIndex(Room room) {
		if (room.getRoomType() == Room.PRIVATE_ROOM_TYPE) {
			if (!privateRoomCodes.add(room.getRoomId(), room.getRoomCode())) {
				throw new IllegalStateException("failed to reserve private roomCode");
			}
		}
	}

}
