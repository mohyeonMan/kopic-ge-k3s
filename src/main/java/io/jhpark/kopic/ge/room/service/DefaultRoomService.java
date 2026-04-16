package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.dto.KopicEnvelope;
import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import io.jhpark.kopic.ge.room.dto.Participant;
import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import io.jhpark.kopic.ge.room.registry.RoomSessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultRoomService implements RoomService {

	private static final String CLOSE_IF_EMPTY_TIMER_KEY = "close-if-empty";
	private static final Duration CLOSE_IF_EMPTY_DELAY = Duration.ofSeconds(30);

	private final RoomSessionStore sessionStore;
	private final RoomRunner roomRunner;
	private final CommonMapper commonMapper;
	private final OutboundBroadcaster roomBroadcaster;

	@Override
	public synchronized RoomSnapshot bootstrapRoom(
		String roomId,
		String ownerEngineId,
		String roomType,
		String hostSessionId,
		int capacity
	) {
		Optional<RoomSnapshot> existing = findRoom(roomId);
		if (existing.isPresent()) {
			return existing.get();
		}

		Room room = new Room(roomId);
		sessionStore.put(new RoomSession(room));
		log.info("room actor bootstrapped. roomId={}", roomId);
		return RoomSnapshot.from(room);
	}

	@Override
	public Optional<RoomSnapshot> findRoom(String roomId) {
		return sessionStore.find(roomId)
			.map(RoomSession::getRoom)
			.map(RoomSnapshot::from);
	}

	@Override
	public boolean canJoin(String roomId, String sessionId) {
		return sessionStore.find(roomId)
			.map(RoomSession::getRoom)
			.map(room -> !room.getParticipants().containsKey(sessionId))
			.orElse(false);
	}

	@Override
	public RoomSubmitResult join(String roomId, String sessionId, String nickname, String wsNodeId) {
		return submit(
			new RoomJob(
				roomId,
				session -> {
					Room room = session.getRoom();
					if (room.getParticipants().containsKey(sessionId)) {
						return RoomJob.Result.none();
					}
					room.getParticipants().put(sessionId, new Participant(wsNodeId, sessionId, nickname));
					room.increaseVersion();

					RoomSnapshot snapshot = RoomSnapshot.from(room);
					for (Participant participant : room.getParticipants().values()) {
						if (!Objects.equals(participant.sessionId(), sessionId)) {
							sendToParticipant(participant, 2100, snapshot);
						}
					}
					return RoomJob.Result.cancelTimer(CLOSE_IF_EMPTY_TIMER_KEY);
				}
			),
			new RoomJobMeta(sessionId, wsNodeId, null)
		);
	}

	@Override
	public RoomSubmitResult leave(String roomId, String sessionId, String wsNodeId) {
		return submit(
			new RoomJob(
				roomId,
				session -> {
					Room room = session.getRoom();
					Participant removed = room.getParticipants().remove(sessionId);
					if (removed == null) {
						return RoomJob.Result.none();
					}
					room.increaseVersion();
					RoomSnapshot snapshot = RoomSnapshot.from(room);
					for (Participant participant : room.getParticipants().values()) {
						sendToParticipant(participant, 2101, snapshot);
					}
					if (room.getParticipants().isEmpty()) {
						return RoomJob.Result.schedule(
							CLOSE_IF_EMPTY_TIMER_KEY,
							CLOSE_IF_EMPTY_DELAY,
							closeIfEmptyJob(room.getRoomId())
						);
					}
					return RoomJob.Result.none();
				}
			),
			new RoomJobMeta(sessionId, wsNodeId, null)
		);
	}

	@Override
	public RoomSubmitResult snapshot(String roomId, String sessionId, String requestId, String wsNodeId) {
		return submit(
			new RoomJob(
				roomId,
				session -> {
					Room room = session.getRoom();
					log.info("snapshot requested. roomId={}, sessionId={}, requestId={}",
						room.getRoomId(), sessionId, requestId);
					Participant participant = room.getParticipants().get(sessionId);
					if (participant == null) {
						return RoomJob.Result.none();
					}
					sendToParticipant(participant, 2102, RoomSnapshot.from(room));
					return RoomJob.Result.none();
				}
			),
			new RoomJobMeta(sessionId, wsNodeId, requestId)
		);
	}

	private RoomSubmitResult submit(RoomJob job, RoomJobMeta meta) {
		RoomSubmitResult result = roomRunner.submit(job, meta);
		if (result instanceof RoomSubmitResult.Rejected rejected) {
			emitRejected(meta, rejected);
		}
		return result;
	}

	@Override
	public void closeRoom(String roomId) {
		sessionStore.find(roomId).ifPresent(session -> {
			if (sessionStore.remove(roomId, session)) {
				session.close();
				log.info("room actor closed by service. roomId={}", roomId);
			}
		});
	}

	private RoomJob closeIfEmptyJob(String roomId) {
		return new RoomJob(
			roomId,
			session -> RoomJob.Result.requestCloseIfEmpty()
		);
	}

	private void sendToParticipant(Participant participant, int eventCode, Object payload) {
		if (participant == null || isBlank(participant.wsNodeId()) || isBlank(participant.sessionId())) {
			return;
		}
		String body = commonMapper.write(payload);
		if (body == null) {
			return;
		}
		roomBroadcaster.send(
			participant.wsNodeId(),
			new GeEvent(
				participant.sessionId(),
				new KopicEnvelope(eventCode, body),
				Instant.now().toString()
			)
		);
	}

	private void emitRejected(RoomJobMeta meta, RoomSubmitResult.Rejected rejected) {
		if (isBlank(rejected.sessionId())) {
			log.warn("room submit rejected without session target. reason={}, message={}",
				rejected.reason(), rejected.message());
			return;
		}
		roomBroadcaster.send(
			resolveWsNodeId(meta, rejected),
			new GeEvent(
				rejected.sessionId(),
				new KopicEnvelope(1999, commonMapper.write(rejectedPayload(rejected))),
				Instant.now().toString()
			)
		);
	}

	private String resolveWsNodeId(RoomJobMeta meta, RoomSubmitResult.Rejected rejected) {
		if (!isBlank(rejected.wsNodeId())) {
			return rejected.wsNodeId();
		}
		if (meta != null && !isBlank(meta.wsNodeId())) {
			return meta.wsNodeId();
		}
		return null;
	}

	private Map<String, Object> rejectedPayload(RoomSubmitResult.Rejected rejected) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reason", rejected.reason().name());
		payload.put("message", rejected.message());
		payload.put("sessionId", rejected.sessionId());
		if (rejected.wsNodeId() != null) {
			payload.put("wsNodeId", rejected.wsNodeId());
		}
		if (rejected.requestId() != null) {
			payload.put("requestId", rejected.requestId());
		}
		return payload;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

}
