package io.jhpark.kopic.ge.room.directory;

import io.jhpark.kopic.ge.common.config.DirectoryProperties;
import io.jhpark.kopic.ge.common.config.NodeProperties;
import io.jhpark.kopic.ge.room.dto.Room;
import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisRoomDirectory implements RoomDirectory {

	private static final String ACTIVE = "ACTIVE";
	private static final String DRAINING = "DRAINING";

	private final StringRedisTemplate redisTemplate;
	private final DirectoryProperties directoryProperties;
	private final NodeProperties nodeProperties;

	@Override
	public boolean addPrivateRoomCode(String roomId, String roomCode) {
		if (!directoryProperties.enabled()) {
			return true;
		}
		if (isBlank(roomId) || isBlank(roomCode)) {
			return false;
		}
		String key = directoryProperties.roomCodeKey(roomCode);
		try {
			Boolean reserved = redisTemplate.opsForValue().setIfAbsent(
				key,
				geId(),
				directoryProperties.roomCodeTtl()
			);
			if (Boolean.TRUE.equals(reserved)) {
				log.debug("private roomCode added to directory. roomCode={}, roomId={}, geId={}",
					roomCode, roomId, geId());
				return true;
			}

			String existingGeId = redisTemplate.opsForValue().get(key);
			if (geId().equals(existingGeId)) {
				redisTemplate.opsForValue().set(key, geId(), directoryProperties.roomCodeTtl());
				log.debug("private roomCode directory ttl refreshed. roomCode={}, roomId={}, geId={}",
					roomCode, roomId, geId());
				return true;
			}

			log.warn("private roomCode add rejected because key already exists. roomCode={}, ownerGeId={}, geId={}",
				roomCode, existingGeId, geId());
			return false;
		} catch (RuntimeException runtimeException) {
			log.warn("private roomCode add failed. roomCode={}, roomId={}, geId={}, error={}",
				roomCode,
				roomId,
				geId(),
				runtimeException.getMessage());
			log.debug("private roomCode add failure detail. roomCode={}, roomId={}, geId={}",
				roomCode,
				roomId,
				geId(),
				runtimeException);
			return false;
		}
	}

	@Override
	public void removePrivateRoomCode(String roomCode) {
		runDirectoryUpdate("remove-private-room-code", () -> {
			if (isBlank(roomCode)) {
				return;
			}
			redisTemplate.delete(directoryProperties.roomCodeKey(roomCode));
			log.debug("private roomCode removed from directory. roomCode={}, geId={}", roomCode, geId());
		});
	}

	@Override
	public void addQuickAvailability(Room room) {
		runDirectoryUpdate("add-quick-availability", () -> {
			if (room == null || isBlank(room.getRoomId())) {
				return;
			}
			String roomId = room.getRoomId();
			if (!isActive()) {
				redisTemplate.opsForZSet().remove(directoryProperties.keys().quickAvailable(), quickMember(roomId));
				return;
			}
			if (room.getRoomType() != Room.QUICK_ROOM_TYPE || !hasCapacity(room)) {
				redisTemplate.opsForZSet().remove(directoryProperties.keys().quickAvailable(), quickMember(roomId));
				log.debug("quick availability add skipped. geId={}, roomId={}", geId(), roomId);
				return;
			}
			double score = quickAvailabilityScore();
			redisTemplate.opsForZSet().add(
				directoryProperties.keys().quickAvailable(),
				quickMember(roomId),
				score
			);
			log.debug("quick availability added. geId={}, roomId={}, score={}",
				geId(), roomId, score);
		});
	}

	@Override
	public void removeQuickAvailability(String roomId) {
		runDirectoryUpdate("remove-quick-availability", () -> {
			if (isBlank(roomId)) {
				return;
			}
			redisTemplate.opsForZSet().remove(directoryProperties.keys().quickAvailable(), quickMember(roomId));
			log.debug("quick availability removed. geId={}, roomId={}", geId(), roomId);
		});
	}

	@Scheduled(
		fixedDelayString = "${kopic.directory.heartbeat-interval-ms:5000}",
		initialDelayString = "${kopic.directory.initial-delay-ms:2000}"
	)
	public void heartbeat() {
		runDirectoryUpdate("heartbeat", () -> {
			redisTemplate.opsForValue().set(
				directoryProperties.geKey(geId()),
				status(),
				directoryProperties.heartbeatTtl()
			);
			log.debug("ge heartbeat refreshed. geId={}, status={}", geId(), status());
		});
	}

	@Override
	public void refresh(List<Room> rooms) {
		runDirectoryUpdate("reconcile", () -> {
			refreshPrivateRoomCodes(rooms);
			refreshQuickAvailability(rooms);
			updateLoad(rooms);
			log.debug("room directory reconciled. geId={}, roomCount={}", geId(), rooms.size());
		});
	}

	@PreDestroy
	public void markDraining() {
		if (!directoryProperties.enabled()) {
			return;
		}
		try {
			redisTemplate.opsForValue().set(
				directoryProperties.geKey(geId()),
				DRAINING,
				directoryProperties.heartbeatTtl()
			);
			removeQuickAvailabilityForCurrentGe();
			removeLoadForCurrentGe();
			log.info("ge marked as draining in directory. geId={}", geId());
		} catch (RuntimeException runtimeException) {
			log.debug("ge draining marker skipped. geId={}, error={}", geId(), runtimeException.getMessage());
		}
	}

	private void refreshPrivateRoomCodes(List<Room> rooms) {
		for (Room room : rooms) {
			if (room.getRoomType() != Room.PRIVATE_ROOM_TYPE || isBlank(room.getRoomCode())) {
				continue;
			}
			String key = directoryProperties.roomCodeKey(room.getRoomCode());
			String existingGeId = redisTemplate.opsForValue().get(key);
			if (!isBlank(existingGeId) && !geId().equals(existingGeId)) {
				log.warn(
					"private roomCode directory conflict detected. roomCode={}, ownerGeId={}, currentGeId={}",
					room.getRoomCode(),
					existingGeId,
					geId()
				);
				continue;
			}
			redisTemplate.opsForValue().set(key, geId(), directoryProperties.roomCodeTtl());
		}
	}

	private void refreshQuickAvailability(List<Room> rooms) {
		removeQuickAvailabilityForCurrentGe();
		for (Room room : rooms) {
			if (room.getRoomType() != Room.QUICK_ROOM_TYPE || !hasCapacity(room)) {
				continue;
			}
			double score = quickAvailabilityScore();
			redisTemplate.opsForZSet().add(
				directoryProperties.keys().quickAvailable(),
				quickMember(room.getRoomId()),
				score
			);
		}
	}

	@Override
	public void refreshLoad(List<Room> rooms) {
		runDirectoryUpdate("refresh-load", () -> updateLoad(rooms));
	}

	private void updateLoad(List<Room> rooms) {
		int roomCount = rooms.size();
		int participantCount = 0;
		for (Room room : rooms) {
			participantCount += room.getParticipants().size();
		}
		double loadScore = participantCount + roomCount * directoryProperties.roomWeight();
		redisTemplate.opsForZSet().add(directoryProperties.keys().geLoad(), geId(), loadScore);
		log.debug("ge load refreshed. geId={}, roomCount={}, participantCount={}, score={}",
			geId(), roomCount, participantCount, loadScore);
	}

	private double quickAvailabilityScore() {
		return Instant.now().toEpochMilli();
	}

	private void removeQuickAvailabilityForCurrentGe() {
		String prefix = geId() + ":";
		Set<String> members = redisTemplate.opsForZSet().range(directoryProperties.keys().quickAvailable(), 0, -1);
		if (members == null || members.isEmpty()) {
			return;
		}
		for (String member : members) {
			if (member != null && member.startsWith(prefix)) {
				redisTemplate.opsForZSet().remove(directoryProperties.keys().quickAvailable(), member);
			}
		}
	}

	private void removeLoadForCurrentGe() {
		redisTemplate.opsForZSet().remove(directoryProperties.keys().geLoad(), geId());
	}

	private boolean hasCapacity(Room room) {
		return room.getParticipants().size() < room.getCapacity();
	}

	private String quickMember(String roomId) {
		return geId() + ":" + roomId;
	}

	private void runDirectoryUpdate(String action, Runnable update) {
		if (!directoryProperties.enabled()) {
			return;
		}
		try {
			update.run();
		} catch (RuntimeException runtimeException) {
			log.warn("room directory update failed. action={}, geId={}, error={}",
				action,
				geId(),
				runtimeException.getMessage());
			log.debug("room directory update failure detail. action={}, geId={}", action, geId(), runtimeException);
		}
	}

	private boolean isActive() {
		return ACTIVE.equalsIgnoreCase(status());
	}

	private String status() {
		return directoryProperties.status();
	}

	private String geId() {
		return nodeProperties.nodeId();
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
