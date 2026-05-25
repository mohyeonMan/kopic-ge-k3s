package io.jhpark.kopic.ge.room.registry;

import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public final class InMemoryQuickRoomCandidateStore {

	private static final Comparator<QuickRoomRef> QUICK_ROOM_ORDER =
		Comparator.comparing(QuickRoomRef::availableAt);

	private final NavigableSet<QuickRoomRef> quickRoomIds = new TreeSet<>(QUICK_ROOM_ORDER);
	private final Map<String, QuickRoomRef> quickRoomRefs = new HashMap<>();
	private final Object lock = new Object();

	Optional<String> findFirstAvailable(Function<String, RoomSession> sessionResolver) {
		synchronized (lock) {
			Iterator<QuickRoomRef> iterator = quickRoomIds.iterator();
			while (iterator.hasNext()) {
				QuickRoomRef ref = iterator.next();
				String roomId = ref.roomId();
				RoomSession session = sessionResolver.apply(roomId);
				if (session == null) {
					iterator.remove();
					quickRoomRefs.remove(roomId);
					continue;
				}

				Room room = session.getRoom();
				if (room.getRoomType() != Room.QUICK_ROOM_TYPE) {
					iterator.remove();
					quickRoomRefs.remove(roomId);
					continue;
				}

				if (hasCapacity(room)) {
					return Optional.of(roomId);
				}
			}
		}
		return Optional.empty();
	}

	boolean add(String roomId) {
		synchronized (lock) {
			// String roomId = room.getRoomId();
			if (quickRoomRefs.containsKey(roomId)) {
				return false;
			}
			QuickRoomRef ref = new QuickRoomRef(roomId, Instant.now());
			quickRoomIds.add(ref);
			quickRoomRefs.put(roomId, ref);
			return true;
		}
	}

	boolean remove(String roomId) {
		synchronized (lock) {
			QuickRoomRef ref = quickRoomRefs.remove(roomId);
			if (ref == null) {
				return false;
			}
			quickRoomIds.remove(ref);
			return true;
		}
	}

	String idsForLog() {
		synchronized (lock) {
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
	}

	private boolean hasCapacity(Room room) {
		return room.getParticipants().size() < room.getCapacity();
	}

	private record QuickRoomRef(String roomId, Instant availableAt) {}
}
