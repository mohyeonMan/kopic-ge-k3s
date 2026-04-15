package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.room.dto.Participant;
import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import io.jhpark.kopic.ge.room.registry.RoomSessionStore;
import java.time.Instant;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRoomService implements RoomService {

	private static final int DEFAULT_CAPACITY = 8;
	private static final Duration EMPTY_ROOM_CLOSE_DELAY = Duration.ofSeconds(30);

	private final RoomSessionStore sessionStore;
	private final RoomRunner roomRunner;
	private final CommonMapper commonMapper;

	@Override
	public synchronized RoomSnapshot bootstrapRoom(
		String roomId,
		String ownerEngineId,
		String roomType,
		String hostUserId,
		int capacity
	) {
		Optional<RoomSnapshot> existing = findRoom(roomId);
		if (existing.isPresent()) {
			return existing.get();
		}

		Room room = new Room(
			roomId,
			null,
			normalizeRoomType(roomType),
			new LinkedHashMap<>(),
			"LOBBY",
			Instant.now(),
			hostUserId,
			ownerEngineId,
			1L,
			normalizeCapacity(capacity)
		);
		sessionStore.put(new RoomSession(room));
		log.info("room bootstrapped. roomId={}, ownerEngineId={}, roomType={}, capacity={}",
			roomId, ownerEngineId, room.getRoomType(), room.getCapacity());
		return RoomSnapshot.from(room);
	}

	@Override
	public Optional<RoomSnapshot> findRoom(String roomId) {
		return sessionStore.find(roomId)
			.map(RoomSession::room)
			.map(RoomSnapshot::from);
	}

	@Override
	public boolean canJoin(String roomId, String userId) {
		return sessionStore.find(roomId)
			.map(RoomSession::room)
			.map(room -> room.getParticipants().containsKey(userId)
				|| room.getParticipants().size() < room.getCapacity())
			.orElse(false);
	}

	@Override
	public void join(String roomId, String userId, String nickname, String ownerEngineId, String roomType, int capacity) {
		if (nickname == null || nickname.isBlank()) {
			throw new IllegalArgumentException("nickname must not be blank");
		}
		bootstrapRoom(roomId, ownerEngineId, roomType, userId, capacity);
		submit(roomId, joinAction(userId, nickname));
	}

	@Override
	public void leave(String roomId, String userId) {
		submit(roomId, leaveAction(userId));
	}

	@Override
	public void snapshot(String roomId, String userId, String requestId) {
		submit(roomId, snapshotAction(userId, requestId));
	}

	@Override
	public void submit(String roomId, RoomAction action) {
		roomRunner.submit(roomId, action);
	}

	@Override
	public void closeRoom(String roomId) {
		sessionStore.find(roomId).ifPresent(session -> {
			sessionStore.remove(roomId);
			log.info("room closed. roomId={}", roomId);
		});
	}

	private String normalizeRoomType(String roomType) {
		return roomType == null || roomType.isBlank()
			? "PRIVATE"
			: roomType.trim().toUpperCase(Locale.ROOT);
	}

	private int normalizeCapacity(int capacity) {
		return capacity <= 0 ? DEFAULT_CAPACITY : capacity;
	}

	private RoomAction joinAction(String userId, String nickname) {
		return ctx -> {
			Room room = ctx.room();
			if (room.getParticipants().containsKey(userId)) {
				return;
			}
			if (room.getParticipants().size() >= room.getCapacity()) {
				throw new IllegalStateException("room is full");
			}

			room.getParticipants().put(userId, new Participant(userId, nickname));
			if (room.getHostUserId() == null || room.getHostUserId().isBlank()) {
				room.setHostUserId(userId);
			}
			room.increaseVersion();
			log.info("participant joined room. roomId={}, userId={}, participantCount={}",
				room.getRoomId(), userId, room.getParticipants().size());
		};
	}

	private RoomAction leaveAction(String userId) {
		return ctx -> {
			Room room = ctx.room();
			Participant removed = room.getParticipants().remove(userId);
			if (removed == null) {
				return;
			}

			if (userId.equals(room.getHostUserId())) {
				room.setHostUserId(room.getParticipants().keySet().stream().findFirst().orElse(null));
			}
			room.increaseVersion();

			if (room.getParticipants().isEmpty()) {
				log.info("room became empty. roomId={}, scheduling close in {} ms",
					room.getRoomId(), EMPTY_ROOM_CLOSE_DELAY.toMillis());
				ctx.after(EMPTY_ROOM_CLOSE_DELAY, closeIfEmptyAction());
				return;
			}

			log.info("participant left room. roomId={}, userId={}, participantCount={}",
				room.getRoomId(), userId, room.getParticipants().size());
		};
	}

	private RoomAction snapshotAction(String userId, String requestId) {
		return ctx -> {
			Room room = ctx.room();
			if (!room.getParticipants().containsKey(userId)) {
				log.info("snapshot ignored for non-participant. roomId={}, userId={}",
					room.getRoomId(), userId);
				return;
			}
			log.info("room snapshot. roomId={}, userId={}, requestId={}, snapshot={}",
				room.getRoomId(),
				userId,
				requestId,
				commonMapper.write(RoomSnapshot.from(room)));
		};
	}

	private RoomAction closeIfEmptyAction() {
		return ctx -> {
			if (ctx.room().getParticipants().isEmpty()) {
				ctx.closeRoom();
			}
		};
	}
}
