package io.jhpark.kopic.ge.room.registry;

import io.jhpark.kopic.ge.room.dto.Room;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InMemoryRoomSessionStore implements RoomSessionStore {

	private static final Comparator<QuickRoomRef> QUICK_ROOM_ORDER =
		Comparator.comparing(QuickRoomRef::createdAt)
			.thenComparing(QuickRoomRef::roomId);

	private final Map<String, RoomSession> sessions = new ConcurrentHashMap<>();
	private final Map<String, String> privateRoomCodeToRoomId = new ConcurrentHashMap<>();
	private final Map<String, String> privateRoomIdToCode = new ConcurrentHashMap<>();
	private final NavigableSet<QuickRoomRef> quickRoomIds = new TreeSet<>(QUICK_ROOM_ORDER);
	private final Map<String, QuickRoomRef> quickRoomRefs = new HashMap<>();
	private final Object quickRoomLock = new Object();

	@Override
	public Optional<RoomSession> find(String roomId) {
		return Optional.ofNullable(sessions.get(roomId));
	}

	@Override
	public Optional<String> findRoomIdByPrivateCode(String roomCode) {
		if (roomCode == null || roomCode.isBlank()) {
			log.debug("private room lookup skipped due to blank roomCode");
			return Optional.empty();
		}
		String roomId = privateRoomCodeToRoomId.get(roomCode);
		if (roomId == null) {
			log.warn("private room lookup miss. roomCode={}", roomCode);
			return Optional.empty();
		}
		if (!sessions.containsKey(roomId)) {
			privateRoomCodeToRoomId.remove(roomCode, roomId);
			privateRoomIdToCode.remove(roomId, roomCode);
			log.warn(
				"private room lookup found stale index and cleaned up. roomCode={}, roomId={}",
				roomCode,
				roomId
			);
			return Optional.empty();
		}
		log.debug("private room lookup hit. roomCode={}, roomId={}", roomCode, roomId);
		return Optional.of(roomId);
	}

	@Override
	public void put(RoomSession session) {
		Room room = session.getRoom();
		String roomId = room.getRoomId();
		sessions.put(roomId, session);
		if (room.getRoomType() == Room.PRIVATE_ROOM_TYPE) {
			indexPrivateRoom(room);
		}
		if (room.getRoomType() == Room.QUICK_ROOM_TYPE) {
			addQuickJoinCandidate(roomId);
		}
	}

	@Override
	public void remove(String roomId) {
		sessions.remove(roomId);
		removePrivateRoomIndex(roomId);
		removeQuickJoinCandidate(roomId);
	}

	@Override
	public boolean remove(String roomId, RoomSession expected) {
		boolean removed = sessions.remove(roomId, expected);
		if (removed) {
			removePrivateRoomIndex(roomId);
			removeQuickJoinCandidate(roomId);
		}
		return removed;
	}

	@Override
	public Optional<String> findFirstAvailableQuickRoomId() {
		synchronized (quickRoomLock) {
			Iterator<QuickRoomRef> iterator = quickRoomIds.iterator();
			while (iterator.hasNext()) {
				QuickRoomRef ref = iterator.next();
				String roomId = ref.roomId();
				RoomSession session = sessions.get(roomId);
				if (session == null) {
					iterator.remove();
					quickRoomRefs.remove(roomId);
					continue;
				}

				Room room = session.getRoom();
				if (room.getRoomType() != Room.QUICK_ROOM_TYPE) {
					iterator.remove();
					continue;
				}

				if (room.getParticipants().size() < room.getCapacity()) {
					log.debug(
						"quick join candidate selected. roomId={}, quickJoinIds={}",
						roomId,
						quickRoomIdsForLog()
					);
					return Optional.of(roomId);
				}
			}
			log.warn("no quick join candidate available. quickJoinIds={}", quickRoomIdsForLog());
		}
		return Optional.empty();
	}

	@Override
	public void addQuickJoinCandidate(String roomId) {
		if (roomId == null || roomId.isBlank()) {
			return;
		}
		RoomSession session = sessions.get(roomId);
		if (session == null) {
			return;
		}
		Room room = session.getRoom();
		if (room.getRoomType() != Room.QUICK_ROOM_TYPE) {
			return;
		}
		if (room.getParticipants().size() >= room.getCapacity()) {
			return;
		}
		synchronized (quickRoomLock) {
			if (quickRoomRefs.containsKey(roomId)) {
				log.debug(
					"quick join candidate already indexed. roomId={}, quickJoinIds={}",
					roomId,
					quickRoomIdsForLog()
				);
				return;
			}
			insertQuickRoomSorted(room);
			log.debug(
				"quick join candidate added. roomId={}, quickJoinIds={}",
				roomId,
				quickRoomIdsForLog()
			);
		}
	}

	@Override
	public void removeQuickJoinCandidate(String roomId) {
		synchronized (quickRoomLock) {
			QuickRoomRef removed = removeFromQuickRoomIndex(roomId);
			if (removed == null) {
				log.debug(
					"quick join candidate remove skipped because room was not indexed. roomId={}, quickJoinIds={}",
					roomId,
					quickRoomIdsForLog()
				);
				return;
			}
			log.debug(
				"quick join candidate removed. roomId={}, quickJoinIds={}",
				roomId,
				quickRoomIdsForLog()
			);
		}
	}

	private void insertQuickRoomSorted(Room room) {
		QuickRoomRef ref = new QuickRoomRef(room.getRoomId(), room.getCreatedAt());
		quickRoomIds.add(ref);
		quickRoomRefs.put(room.getRoomId(), ref);
	}

	private QuickRoomRef removeFromQuickRoomIndex(String roomId) {
		QuickRoomRef ref = quickRoomRefs.remove(roomId);
		if (ref != null) {
			quickRoomIds.remove(ref);
		}
		return ref;
	}

	private void indexPrivateRoom(Room room) {
		String roomCode = room.getRoomCode();
		if (roomCode == null || roomCode.isBlank()) {
			log.warn(
				"private room index skipped due to blank roomCode. roomId={}, roomType={}",
				room.getRoomId(),
				room.getRoomType()
			);
			return;
		}
		privateRoomCodeToRoomId.put(roomCode, room.getRoomId());
		privateRoomIdToCode.put(room.getRoomId(), roomCode);
		log.debug("private room indexed. roomCode={}, roomId={}", roomCode, room.getRoomId());
	}

	private void removePrivateRoomIndex(String roomId) {
		String roomCode = privateRoomIdToCode.remove(roomId);
		if (roomCode == null || roomCode.isBlank()) {
			log.debug("private room index remove skipped because room was not indexed. roomId={}", roomId);
			return;
		}
		privateRoomCodeToRoomId.remove(roomCode, roomId);
		log.debug("private room index removed. roomCode={}, roomId={}", roomCode, roomId);
	}

	private String quickRoomIdsForLog() {
		StringBuilder builder = new StringBuilder("[");
		Iterator<QuickRoomRef> iterator = quickRoomIds.iterator();
		while (iterator.hasNext()) {
			QuickRoomRef ref = iterator.next();
			builder.append(ref.roomId());
			if (iterator.hasNext()) {
				builder.append(", ");
			}
		}
		builder.append("]");
		return builder.toString();
	}

	private record QuickRoomRef(String roomId, Instant createdAt) {}
}
