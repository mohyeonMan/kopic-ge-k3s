package io.jhpark.kopic.ge.inbound.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.jhpark.kopic.ge.common.dto.KopicEnvelope;
import io.jhpark.kopic.ge.common.util.EventMapper;
import io.jhpark.kopic.ge.inbound.dto.WsEvent;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import io.jhpark.kopic.ge.room.service.OutboundBroadcaster;
import io.jhpark.kopic.ge.room.service.RoomService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultEventHandler {

	private final RoomService roomService;
	private final OutboundBroadcaster roomBroadcaster;
	private final EventMapper eventMapper;

	public void handle(WsEvent event) {
		if (event == null || event.envelope() == null) {
			return;
		}

		switch (event.envelope().e()) {
			case 1100 -> handleJoin(event);
			case 1101 -> handleLeave(event);
			case 1102 -> handleSnapshot(event);
			default -> {
				emitRejected(
					event.senderSessionId(),
					event.wsNodeId(),
					"UNSUPPORTED_EVENT",
					"unsupported event code: " + event.envelope().e()
				);
			}
		}
	}

	private void handleJoin(WsEvent event) {
		JsonNode payload = parsePayload(event, "roomId", "sessionId", "nickname");
		if (payload == null) {
			return;
		}

		String roomId = eventMapper.text(payload, "roomId");
		String sessionId = eventMapper.text(payload, "sessionId");
		String nickname = eventMapper.text(payload, "nickname");

		roomService.join(
			roomId,
			sessionId,
			nickname,
			event.wsNodeId()
		);
	}

	private void handleLeave(WsEvent event) {
		JsonNode payload = parsePayload(event, "roomId", "sessionId");
		if (payload == null) {
			return;
		}

		String roomId = eventMapper.text(payload, "roomId");
		String sessionId = eventMapper.text(payload, "sessionId");

		roomService.leave(roomId, sessionId, event.wsNodeId());
	}

	private void handleSnapshot(WsEvent event) {
		JsonNode payload = parsePayload(event, "roomId", "sessionId", "requestId");
		if (payload == null) {
			return;
		}

		String roomId = eventMapper.text(payload, "roomId");
		String sessionId = eventMapper.text(payload, "sessionId");
		String requestId = eventMapper.text(payload, "requestId");

		roomService.snapshot(
			roomId,
			sessionId,
			requestId,
			event.wsNodeId()
		);
	}

	private void emitRejected(
		String sessionId,
		String wsNodeId,
		String reason,
		String message
	) {
		if (isBlank(sessionId)) {
			log.warn("event rejected without session target. reason={}, message={}", reason, message);
			return;
		}
		roomBroadcaster.send(
			wsNodeId,
			new GeEvent(
				sessionId,
				new KopicEnvelope(1999, eventMapper.write(rejectedPayload(reason, message))),
				Instant.now().toString()
			)
		);
	}

	private Map<String, Object> rejectedPayload(
		String reason,
		String message
	) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("reason", reason);
		payload.put("message", message);
		return payload;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private JsonNode parsePayload(WsEvent event, String... requiredFields) {
		String rawPayload = event != null && event.envelope() != null ? event.envelope().p() : null;
		try {
			return eventMapper.parse(rawPayload, requiredFields);
		} catch (IllegalArgumentException illegalArgumentException) {
			JsonNode fallback = eventMapper.parse(rawPayload);
			emitRejected(
				eventMapper.text(fallback, "sessionId"),
				event.wsNodeId(),
				"INVALID_REQUEST",
				illegalArgumentException.getMessage()
			);
			return null;
		}
	}
}
