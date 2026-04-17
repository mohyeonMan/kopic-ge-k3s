package io.jhpark.kopic.ge.inbound.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.jhpark.kopic.ge.common.dto.KopicEnvelope;
import io.jhpark.kopic.ge.common.util.EventMapper;
import io.jhpark.kopic.ge.common.util.TimeFormatUtil;
import io.jhpark.kopic.ge.inbound.dto.WsEvent;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import io.jhpark.kopic.ge.room.service.OutboundBroadcaster;
import io.jhpark.kopic.ge.room.service.RoomService;
import io.jhpark.kopic.ge.room.service.RoomSubmitResult;
import java.time.Instant;
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
					new KopicEnvelope(
						1999,
						rejectedPayload("INVALID_REQUEST", "missing event envelope")
					),
					TimeFormatUtil.now()
				)
			);
			return;
		}

		switch (event.envelope().e()) {
			case 101 -> handleJoin(event);
			case 102 -> handleLeave(event);
			case 1102 -> handleSnapshot(event);
			case 201 ->  handleStroke(event);
			case 204 -> handleChat(event);
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
		JsonNode payload = event.envelope().p();
		validateRequired(event, payload, "nickname");

		String roomCode = eventMapper.text(payload, "roomCode");
		String nickname = eventMapper.text(payload, "nickname");

		RoomSubmitResult result;
		if (!isBlank(roomCode)) {
			result = roomService.privateJoin(
				roomCode,
				event.senderSessionId(),
				nickname,
				event.wsNodeId()
			);
		} else {
			result = roomService.quickJoin(
				event.senderSessionId(),
				nickname,
				event.wsNodeId()
			);
		}
		emitResult(event, result);
	}

	private void handleLeave(WsEvent event) {
		JsonNode payload = event.envelope().p();
		validateRequired(event, payload, "roomId");

		String roomId = eventMapper.text(payload, "roomId");

		RoomSubmitResult result = roomService.leave(roomId, event.senderSessionId(), event.wsNodeId());
		emitResult(event, result);
	}

	private void handleSnapshot(WsEvent event) {
		JsonNode payload = event.envelope().p();
		validateRequired(event, payload, "roomId", "requestId");

		String roomId = eventMapper.text(payload, "roomId");
		String requestId = eventMapper.text(payload, "requestId");

		RoomSubmitResult result = roomService.snapshot(
			roomId,
			event.senderSessionId(),
			requestId,
			event.wsNodeId()
		);
		emitResult(event, result);
	}

	private void handleStroke(WsEvent event){

		JsonNode payload = event.envelope().p();
		roomService.drawStroke(
			event.roomId(),
			event.senderSessionId(),
			payload
		);
		
	}

	private void handleChat(WsEvent event) {
		JsonNode payload = event.envelope().p();
		validateRequired(event, payload, "t");

		String text = eventMapper.text(payload, "t");
		roomService.guessChat(
			event.roomId(),
			event.senderSessionId(),
			text
		);

		log.info(event.toString());
	}

	private void emitResult(WsEvent event, RoomSubmitResult result) {
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
				new KopicEnvelope(
					1999,
					rejectedPayload(reason, message)
				),
				Instant.now().toString()
			)
		);
	}

	private ObjectNode rejectedPayload(
		String reason,
		String message
	) {
		return eventMapper.rawMapper()
			.createObjectNode()
			.put("reason", reason)
			.put("message", message);
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

	private void validateRequired(WsEvent event, JsonNode payload, String... requiredFields) {
		try {
			eventMapper.require(payload, requiredFields);
		} catch (IllegalArgumentException illegalArgumentException) {
			emitRejected(
				event.senderSessionId(),
				event.wsNodeId(),
				"INVALID_REQUEST",
				illegalArgumentException.getMessage()
			);
		}
	}

	
	

}
