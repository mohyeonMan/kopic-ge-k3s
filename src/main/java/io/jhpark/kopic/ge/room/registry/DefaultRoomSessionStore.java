package io.jhpark.kopic.ge.room.registry;

import io.jhpark.kopic.ge.room.directory.RoomDirectory;
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
	private final InMemoryPrivateRoomCodeStore privateRoomCodes;
	private final InMemoryQuickRoomCandidateStore quickRoomCandidates;
	private final RoomDirectory roomDirectory;

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
		
		addIndexesAndDirectory(room);
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
	public Optional<String> findFirstAvailableQuickRoomId() {
		Optional<String> roomId = quickRoomCandidates.findFirstAvailable(sessions::get);
		if (roomId.isPresent()) {
			log.debug(
				"quick join candidate selected. roomId={}, quickJoinIds={}",
				roomId.get(),
				quickRoomCandidates.idsForLog()
			);
		} else {
			log.warn("no quick join candidate available. quickJoinIds={}", quickRoomCandidates.idsForLog());
		}
		return roomId;
	}

	@Override
	public void addQuickJoinCandidate(String roomId) {
		Optional<RoomSession> sessionOpt = find(roomId);
		if (!sessionOpt.isPresent()) {
			return;
		}
		Room room = sessionOpt.get().getRoom();
		if (room.getRoomType() != Room.QUICK_ROOM_TYPE) {
			return;
		}
		if (room.getParticipants().size() >= room.getCapacity()) {
			return;
		}
		if (quickRoomCandidates.add(roomId)) {
			roomDirectory.addQuickAvailability(room);
			log.debug(
				"quick join candidate added. roomId={}, quickJoinIds={}",
				room.getRoomId(),
				quickRoomCandidates.idsForLog()
			);
			return;
		}
		log.debug(
			"quick join candidate add skipped. roomId={}, quickJoinIds={}",
			room.getRoomId(),
			quickRoomCandidates.idsForLog()
		);
	}

	@Override
	public void addIndexPrivateRoom(String roomId, String roomCode) {
		privateRoomCodes.indexIfAbsent(roomId, roomCode);
		roomDirectory.addPrivateRoomCode(roomId, roomCode);
	}

	@Override
	public void removeIndexPrivateRoom(String roomId, String roomCode) {
		privateRoomCodes.remove(roomId);
		roomDirectory.removePrivateRoomCode(roomCode);
	}

	@Override
	public void removeQuickJoinCandidate(String roomId) {
		boolean removed = quickRoomCandidates.remove(roomId);
		if (removed) {
			roomDirectory.removeQuickAvailability(roomId);
		}
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
		fixedDelayString = "${kopic.directory.reconciliation-interval-ms:3600000}",
		initialDelayString = "${kopic.directory.initial-delay-ms:2000}"
	)
	public void reconcileDirectory() {
		roomDirectory.refresh(snapshotRooms());
	}

	@Scheduled(
		fixedDelayString = "${kopic.directory.load-interval-ms:5000}",
		initialDelayString = "${kopic.directory.initial-delay-ms:2000}"
	)
	public void refreshLoad() {
		roomDirectory.refreshLoad(snapshotRooms());
	}

	private List<Room> snapshotRooms() {
		ArrayList<Room> rooms = new ArrayList<>();
		for (RoomSession session : sessions.values()) {
			rooms.add(session.getRoom());
		}
		return List.copyOf(rooms);
	}

	public void removeIndexesAndDirectory(Room room) {
		if (room.getRoomType() == Room.PRIVATE_ROOM_TYPE) {
			removeIndexPrivateRoom(room.getRoomId(), room.getRoomCode());
			return;
		}

		if (room.getRoomType() == Room.QUICK_ROOM_TYPE) {
			removeQuickJoinCandidate(room.getRoomId());
			return;
		}
	}

	public void addIndexesAndDirectory(Room room) {
		if (room.getRoomType() == Room.PRIVATE_ROOM_TYPE) {
			addIndexPrivateRoom(room.getRoomId(), room.getRoomCode());
		}
		if (room.getRoomType() == Room.QUICK_ROOM_TYPE) {
			addQuickJoinCandidate(room.getRoomId());
		}
	}

}
