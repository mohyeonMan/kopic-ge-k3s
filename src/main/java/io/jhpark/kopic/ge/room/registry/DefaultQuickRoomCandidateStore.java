package io.jhpark.kopic.ge.room.registry;

import io.jhpark.kopic.ge.common.config.KopicRedisProperties;
import io.jhpark.kopic.ge.common.redis.RedisService;
import io.jhpark.kopic.ge.common.runtime.GeRuntimeState;
import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NavigableSet;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public final class DefaultQuickRoomCandidateStore {

	private static final Comparator<QuickRoomRef> QUICK_ROOM_ORDER =
		Comparator.comparing(QuickRoomRef::availableAt);

	private final NavigableSet<QuickRoomRef> quickRoomIds = new TreeSet<>(QUICK_ROOM_ORDER);
	private final Map<String, QuickRoomRef> quickRoomRefs = new HashMap<>();
	private final Object lock = new Object();
	private final RoomSessionStore sessionStore;
	private final RedisService redisService;
	private final KopicRedisProperties redisProperties;
	private final GeRuntimeState runtimeState;

	public Optional<String> findFirstAvailableRoomId() {
		if (!runtimeState.isActive()) {
			return Optional.empty();
		}
		synchronized (lock) {
			Iterator<QuickRoomRef> iterator = quickRoomIds.iterator();
			while (iterator.hasNext()) {
				QuickRoomRef ref = iterator.next();
				String roomId = ref.roomId();
				RoomSession session = sessionStore.find(roomId).orElse(null);
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
					log.debug(
						"quick join candidate selected. roomId={}, quickJoinIds={}",
						roomId,
						idsForLog()
					);
					return Optional.of(roomId);
				}
			}
		}
		log.warn("no quick join candidate available. quickJoinIds={}", idsForLog());
		return Optional.empty();
	}

	public void add(String roomId) {
		if (!runtimeState.isActive()) {
			return;
		}
		if (isBlank(roomId)) {
			return;
		}
		Optional<RoomSession> sessionOpt = sessionStore.find(roomId);
		if (sessionOpt.isEmpty()) {
			return;
		}
		Room room = sessionOpt.get().getRoom();
		if (room.getRoomType() != Room.QUICK_ROOM_TYPE) {
			return;
		}
		if (!hasCapacity(room)) {
			return;
		}
		if (addIndex(roomId)) {
			addRedisQuickAvailability(room);
			log.debug(
				"quick join candidate added. roomId={}, quickJoinIds={}",
				room.getRoomId(),
				idsForLog()
			);
			return;
		}
		log.debug(
			"quick join candidate add skipped. roomId={}, quickJoinIds={}",
			room.getRoomId(),
			idsForLog()
		);
	}

	private boolean addIndex(String roomId) {
		synchronized (lock) {
			if (quickRoomRefs.containsKey(roomId)) {
				return false;
			}
			QuickRoomRef ref = new QuickRoomRef(roomId, Instant.now());
			quickRoomIds.add(ref);
			quickRoomRefs.put(roomId, ref);
			return true;
		}
	}

	public boolean remove(String roomId) {
		boolean removed = removeIndex(roomId);
		if (removed) {
			removeRedisQuickAvailability(roomId);
			log.debug(
				"quick join candidate removed. roomId={}, quickJoinIds={}",
				roomId,
				idsForLog()
			);
			return true;
		}
		log.debug(
			"quick join candidate remove skipped because room was not indexed. roomId={}, quickJoinIds={}",
			roomId,
			idsForLog()
		);
		return false;
	}

	@Scheduled(
		fixedDelayString = "${kopic.redis.reconciliation-interval:1h}",
		initialDelayString = "${kopic.redis.initial-delay:2s}"
	)
	public void cleanupStaleRedisAvailability() {
		if (!redisProperties.enabled() || runtimeState.isDraining()) {
			return;
		}
		try {
			int removedCount = removeStaleRedisAvailabilityForCurrentGe();
			log.debug("stale quick availability cleaned up. geId={}, removedCount={}, quickJoinIds={}",
				geId(), removedCount, idsForLog());
		} catch (RuntimeException runtimeException) {
			log.warn("stale quick availability cleanup failed. geId={}, error={}", geId(), runtimeException.getMessage());
			log.debug("stale quick availability cleanup failure detail. geId={}", geId(), runtimeException);
		}
	}

	public void clearCurrentGeCandidates() {
		if (!redisProperties.enabled()) {
			return;
		}
		try {
			removeQuickAvailabilityForCurrentGe();
			log.info("quick availability cleared for current ge. geId={}", geId());
		} catch (RuntimeException runtimeException) {
			log.info("quick availability cleanup skipped. geId={}, error={}", geId(), runtimeException.getMessage());
		}
	}

	private boolean removeIndex(String roomId) {
		synchronized (lock) {
			QuickRoomRef ref = quickRoomRefs.remove(roomId);
			if (ref == null) {
				return false;
			}
			quickRoomIds.remove(ref);
			return true;
		}
	}

	private String idsForLog() {
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

	private void addRedisQuickAvailability(Room room) {
		if (!redisProperties.enabled() || room == null || isBlank(room.getRoomId())) {
			return;
		}
		String roomId = room.getRoomId();
		try {
			if (!isActive()) {
				removeRedisQuickAvailability(roomId);
				return;
			}
			if (room.getRoomType() != Room.QUICK_ROOM_TYPE || !hasCapacity(room)) {
				removeRedisQuickAvailability(roomId);
				log.debug("quick availability add skipped. geId={}, roomId={}", geId(), roomId);
				return;
			}
			double score = quickAvailabilityScore();
			redisService.zAdd(redisProperties.keys().quickAvailable(), quickMember(roomId), score);
			redisService.sAdd(quickGeRoomsKey(), roomId);
			log.debug("quick availability added. geId={}, roomId={}, score={}", geId(), roomId, score);
		} catch (RuntimeException runtimeException) {
			log.warn("quick availability add failed. geId={}, roomId={}, error={}",
				geId(), roomId, runtimeException.getMessage());
			log.debug("quick availability add failure detail. geId={}, roomId={}", geId(), roomId, runtimeException);
		}
	}

	private void removeRedisQuickAvailability(String roomId) {
		if (!redisProperties.enabled() || isBlank(roomId)) {
			return;
		}
		try {
			redisService.zRemove(redisProperties.keys().quickAvailable(), quickMember(roomId));
			redisService.sRemove(quickGeRoomsKey(), roomId);
			log.debug("quick availability removed. geId={}, roomId={}", geId(), roomId);
		} catch (RuntimeException runtimeException) {
			log.warn("quick availability remove failed. geId={}, roomId={}, error={}",
				geId(), roomId, runtimeException.getMessage());
			log.debug("quick availability remove failure detail. geId={}, roomId={}", geId(), roomId, runtimeException);
		}
	}

	private int removeStaleRedisAvailabilityForCurrentGe() {
		String prefix = geId() + ":";
		Set<String> members = redisService.zRange(redisProperties.keys().quickAvailable(), 0, -1);
		if (members == null || members.isEmpty()) {
			return 0;
		}

		int removedCount = 0;
		for (String member : members) {
			if (member == null || !member.startsWith(prefix)) {
				continue;
			}
			String roomId = member.substring(prefix.length());
			if (isStaleRedisAvailability(roomId)) {
				redisService.zRemove(redisProperties.keys().quickAvailable(), member);
				redisService.sRemove(quickGeRoomsKey(), roomId);
				removedCount += 1;
			}
		}
		return removedCount;
	}

	private void removeQuickAvailabilityForCurrentGe() {
		Set<String> roomIds = redisService.sMembers(quickGeRoomsKey());
		if (roomIds == null || roomIds.isEmpty()) {
			removeQuickAvailabilityForCurrentGeByPrefixScan();
			return;
		}
		for (String roomId : roomIds) {
			if (!isBlank(roomId)) {
				redisService.zRemove(redisProperties.keys().quickAvailable(), quickMember(roomId));
			}
		}
		redisService.delete(quickGeRoomsKey());
	}

	private void removeQuickAvailabilityForCurrentGeByPrefixScan() {
		String prefix = geId() + ":";
		Set<String> members = redisService.zRange(redisProperties.keys().quickAvailable(), 0, -1);
		if (members == null || members.isEmpty()) {
			return;
		}
		for (String member : members) {
			if (member != null && member.startsWith(prefix)) {
				redisService.zRemove(redisProperties.keys().quickAvailable(), member);
			}
		}
	}

	private boolean isStaleRedisAvailability(String roomId) {
		if (isBlank(roomId)) {
			return true;
		}
		if (!containsIndex(roomId)) {
			return true;
		}
		RoomSession session = sessionStore.find(roomId).orElse(null);
		if (session == null) {
			removeIndex(roomId);
			return true;
		}

		Room room = session.getRoom();
		if (room.getRoomType() != Room.QUICK_ROOM_TYPE || !hasCapacity(room)) {
			removeIndex(roomId);
			return true;
		}
		return false;
	}

	private boolean containsIndex(String roomId) {
		synchronized (lock) {
			return quickRoomRefs.containsKey(roomId);
		}
	}

	private double quickAvailabilityScore() {
		return Instant.now().toEpochMilli();
	}

	private boolean isActive() {
		return runtimeState.isActive();
	}

	private String quickMember(String roomId) {
		return geId() + ":" + roomId;
	}

	private String quickGeRoomsKey() {
		return redisProperties.quickGeRoomsKey(geId());
	}

	private String geId() {
		return runtimeState.geId();
	}

	private boolean hasCapacity(Room room) {
		return room.getParticipants().size() < room.getCapacity();
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private record QuickRoomRef(String roomId, Instant availableAt) {}
}
