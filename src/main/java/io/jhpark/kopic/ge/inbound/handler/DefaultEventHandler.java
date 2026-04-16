package io.jhpark.kopic.ge.inbound.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.jhpark.kopic.ge.common.dto.KopicEnvelope;
import io.jhpark.kopic.ge.common.util.EventMapper;
import io.jhpark.kopic.ge.common.util.TimeFormatUtil;
import io.jhpark.kopic.ge.inbound.dto.WsEvent;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import io.jhpark.kopic.ge.room.service.OutboundBroadcaster;
import io.jhpark.kopic.ge.room.service.RoomService;
import io.jhpark.kopic.ge.room.service.RoomSubmitResult;
import java.time.Instant;
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
		if (!validateInboundSessionMeta(event)) {
			log.error("invalid inbound event session meta. drop event.");
			return;
		}

		if (event == null || event.envelope() == null) {
			roomBroadcaster.send(
				event.wsNodeId(),
				new GeEvent(
					event.senderSessionId(),
					new KopicEnvelope(1999, eventMapper.write(rejectedPayload("INVALID_REQUEST", "missing event envelope"))),
					TimeFormatUtil.now()
				)
			);
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
		JsonNode payload = parsePayload(event, "roomId", "nickname");
		if (payload == null) {
			return;
		}

		String roomId = eventMapper.text(payload, "roomId");
		String nickname = eventMapper.text(payload, "nickname");

		RoomSubmitResult result = roomService.join(
			roomId,
			event.senderSessionId(),
			nickname,
			event.wsNodeId()
		);
		emitRejectedIfNeeded(event, result);
	}

	private void handleLeave(WsEvent event) {
		JsonNode payload = parsePayload(event, "roomId");
		if (payload == null) {
			return;
		}

		String roomId = eventMapper.text(payload, "roomId");

		RoomSubmitResult result = roomService.leave(roomId, event.senderSessionId(), event.wsNodeId());
		emitRejectedIfNeeded(event, result);
	}

	private void handleSnapshot(WsEvent event) {
		JsonNode payload = parsePayload(event, "roomId", "requestId");
		if (payload == null) {
			return;
		}

		String roomId = eventMapper.text(payload, "roomId");
		String requestId = eventMapper.text(payload, "requestId");

		RoomSubmitResult result = roomService.snapshot(
			roomId,
			event.senderSessionId(),
			requestId,
			event.wsNodeId()
		);
		emitRejectedIfNeeded(event, result);
	}

	private void emitRejectedIfNeeded(WsEvent event, RoomSubmitResult result) {
		if (!(result instanceof RoomSubmitResult.Rejected rejected)) {
			return;
		}
		emitRejected(
			event.senderSessionId(),
			event.wsNodeId(),
			rejected.reason().name(),
			rejected.message()
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
		return Map.of(
			"reason", reason,
			"message", message
		);
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private boolean validateInboundSessionMeta(WsEvent event) {
		if (event == null || isBlank(event.senderSessionId())) {
			log.warn("drop inbound event due to missing senderSessionId. eventCode={}",
				event != null && event.envelope() != null ? event.envelope().e() : null);
			return false;
		}
		if (isBlank(event.wsNodeId())) {
			log.warn("drop inbound event due to missing wsNodeId. senderSessionId={}, eventCode={}",
				event.senderSessionId(),
				event.envelope() != null ? event.envelope().e() : null);
			return false;
		}
		return true;
	}

	private JsonNode parsePayload(WsEvent event, String... requiredFields) {
		String rawPayload = event != null && event.envelope() != null ? event.envelope().p() : null;
		try {
			return eventMapper.parse(rawPayload, requiredFields);
		} catch (IllegalArgumentException illegalArgumentException) {
			emitRejected(
				event != null ? event.senderSessionId() : null,
				event != null ? event.wsNodeId() : null,
				"INVALID_REQUEST",
				illegalArgumentException.getMessage()
			);
			return null;
		}
	}
}
