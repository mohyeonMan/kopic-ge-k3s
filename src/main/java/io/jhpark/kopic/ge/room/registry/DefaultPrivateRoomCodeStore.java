package io.jhpark.kopic.ge.room.registry;

import io.jhpark.kopic.ge.common.config.KopicRedisProperties;
import io.jhpark.kopic.ge.common.config.NodeProperties;
import io.jhpark.kopic.ge.common.redis.RedisService;
import io.jhpark.kopic.ge.room.dto.Room;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public final class DefaultPrivateRoomCodeStore {

	private final Map<String, String> roomCodeToRoomId = new ConcurrentHashMap<>();
	private final Map<String, String> roomIdToRoomCode = new ConcurrentHashMap<>();
	private final RedisService redisService;
	private final KopicRedisProperties redisProperties;
	private final NodeProperties nodeProperties;

	Optional<String> findRoomId(String roomCode, Predicate<String> roomExists) {
		if (roomCode == null || roomCode.isBlank()) {
			log.debug("private room lookup skipped due to blank roomCode");
			return Optional.empty();
		}
		String roomId = roomCodeToRoomId.get(roomCode);
		if (roomId == null) {
			log.warn("private room lookup miss. roomCode={}", roomCode);
			return Optional.empty();
		}
		if (!roomExists.test(roomId)) {
			roomCodeToRoomId.remove(roomCode, roomId);
			roomIdToRoomCode.remove(roomId, roomCode);
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

	private boolean indexIfAbsent(String roomId, String roomCode) {
		if (roomCode == null || roomCode.isBlank()) {
			log.warn(
				"private room index skipped due to blank roomCode. roomId={}, roomCode={}",
				roomId,
				roomCode
			);
			return false;
		}
		String existingRoomId = roomCodeToRoomId.putIfAbsent(roomCode, roomId);
		if (existingRoomId != null && !existingRoomId.equals(roomId)) {
			log.warn(
				"private room index rejected because roomCode already exists. roomCode={}, roomId={}, existingRoomId={}",
				roomCode,
				roomId,
				existingRoomId
			);
			return false;
		}
		roomIdToRoomCode.put(roomId, roomCode);
		log.debug("private room indexed. roomCode={}, roomId={}", roomCode, roomId);
		return true;
	}

	boolean add(String roomId, String roomCode) {
		if (!addRedisPrivateRoomCode(roomId, roomCode)) {
			return false;
		}
		return indexIfAbsent(roomId, roomCode);
	}

	void remove(String roomId, String roomCode) {
		removeIndex(roomId);
		removeRedisPrivateRoomCode(roomCode);
	}

	void refresh(List<Room> rooms) {
		if (!redisProperties.enabled()) {
			return;
		}
		try {
			for (Room room : rooms) {
				if (room.getRoomType() != Room.PRIVATE_ROOM_TYPE || isBlank(room.getRoomCode())) {
					continue;
				}
				refreshRedisPrivateRoomCode(room);
			}
		} catch (RuntimeException runtimeException) {
			log.warn("private roomCode refresh failed. geId={}, error={}", geId(), runtimeException.getMessage());
			log.debug("private roomCode refresh failure detail. geId={}", geId(), runtimeException);
		}
	}

	private Optional<String> removeIndex(String roomId) {
		String roomCode = roomIdToRoomCode.remove(roomId);
		if (roomCode == null || roomCode.isBlank()) {
			log.debug("private room index remove skipped because room was not indexed. roomId={}", roomId);
			return Optional.empty();
		}
		roomCodeToRoomId.remove(roomCode, roomId);
		log.debug("private room index removed. roomCode={}, roomId={}", roomCode, roomId);
		return Optional.of(roomCode);
	}

	private boolean addRedisPrivateRoomCode(String roomId, String roomCode) {
		if (!redisProperties.enabled()) {
			return true;
		}
		if (isBlank(roomId) || isBlank(roomCode)) {
			return false;
		}
		String key = redisProperties.roomCodeKey(roomCode);
		try {
			Boolean reserved = redisService.setIfAbsent(key, geId(), redisProperties.roomCodeTtl());
			if (Boolean.TRUE.equals(reserved)) {
				log.debug("private roomCode added to redis. roomCode={}, roomId={}, geId={}",
					roomCode, roomId, geId());
				return true;
			}

			String existingGeId = redisService.get(key);
			if (geId().equals(existingGeId)) {
				redisService.set(key, geId(), redisProperties.roomCodeTtl());
				log.debug("private roomCode redis ttl refreshed. roomCode={}, roomId={}, geId={}",
					roomCode, roomId, geId());
				return true;
			}

			log.warn("private roomCode add rejected because redis key already exists. roomCode={}, ownerGeId={}, geId={}",
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

	private void removeRedisPrivateRoomCode(String roomCode) {
		if (!redisProperties.enabled() || isBlank(roomCode)) {
			return;
		}
		try {
			redisService.delete(redisProperties.roomCodeKey(roomCode));
			log.debug("private roomCode removed from redis. roomCode={}, geId={}", roomCode, geId());
		} catch (RuntimeException runtimeException) {
			log.warn("private roomCode remove failed. roomCode={}, geId={}, error={}",
				roomCode, geId(), runtimeException.getMessage());
			log.debug("private roomCode remove failure detail. roomCode={}, geId={}", roomCode, geId(),
				runtimeException);
		}
	}

	private void refreshRedisPrivateRoomCode(Room room) {
		String key = redisProperties.roomCodeKey(room.getRoomCode());
		String existingGeId = redisService.get(key);
		if (!isBlank(existingGeId) && !geId().equals(existingGeId)) {
			log.warn(
				"private roomCode redis conflict detected. roomCode={}, ownerGeId={}, currentGeId={}",
				room.getRoomCode(),
				existingGeId,
				geId()
			);
			return;
		}
		redisService.set(key, geId(), redisProperties.roomCodeTtl());
	}

	private String geId() {
		return nodeProperties.nodeId();
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
