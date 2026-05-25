package io.jhpark.kopic.ge.room.registry;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public final class InMemoryPrivateRoomCodeStore {

	private final Map<String, String> roomCodeToRoomId = new ConcurrentHashMap<>();
	private final Map<String, String> roomIdToRoomCode = new ConcurrentHashMap<>();

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

	boolean indexIfAbsent(String roomId, String roomCode) {
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

	Optional<String> remove(String roomId) {
		String roomCode = roomIdToRoomCode.remove(roomId);
		if (roomCode == null || roomCode.isBlank()) {
			log.debug("private room index remove skipped because room was not indexed. roomId={}", roomId);
			return Optional.empty();
		}
		roomCodeToRoomId.remove(roomCode, roomId);
		log.debug("private room index removed. roomCode={}, roomId={}", roomCode, roomId);
		return Optional.of(roomCode);
	}
}
