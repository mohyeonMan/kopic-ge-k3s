package io.jhpark.kopic.ge.inbound.handler;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.jhpark.kopic.ge.common.dto.KopicEnvelope;
import io.jhpark.kopic.ge.common.util.EventMapper;
import io.jhpark.kopic.ge.common.util.TimeFormatUtil;
import io.jhpark.kopic.ge.inbound.dto.WsEvent;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import io.jhpark.kopic.ge.room.service.GeEventPublisher;
import io.jhpark.kopic.ge.room.service.RoomService;
import io.jhpark.kopic.ge.room.service.RoomSubmitResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultEventHandler {

	private final RoomService roomService;
	private final GeEventPublisher geEventPublisher;
	private final EventMapper eventMapper;

	public void handle(WsEvent event) {
		if (!validateInboundSessionMeta(event)) {
			log.error("invalid inbound event session meta. drop event.");
			
			return;
		}

		if (event == null || event.envelope() == null) {
			geEventPublisher.publish(
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
			case 103 -> handleCreatePrivateRoom(event);
			case 107 -> handleSetting(event);
			case 200 -> handleGameStart(event);
			case 1102 -> handleSnapshot(event);
			case 201 ->  handleStroke(event);
			case 204 -> handleChat(event);
			default -> {
				sendRejected(
					event.senderSessionId(),
					event.wsNodeId(),
					1999,
					"UNSUPPORTED_EVENT",
					"unsupported event code: " + event.envelope().e()
				);
			}
		}
	}

	private void handleCreatePrivateRoom(WsEvent event) {
		JsonNode payload = event.envelope().p();
		if (!validateRequired(event, payload, "nickname")) {
			return;
		}

		String nickname = eventMapper.text(payload, "nickname");
		RoomSubmitResult result = roomService.createPrivateRoom(
			event.senderSessionId(),
			nickname,
			event.wsNodeId()
		);
		emitResult(event, result);
	}

	private void handleJoin(WsEvent event) {
		JsonNode payload = event.envelope().p();
		if (!validateRequired(event, payload, "nickname")) {
			return;
		}

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
		RoomSubmitResult result = roomService.leave(event.roomId(), event.senderSessionId(), event.wsNodeId());
		emitResult(event, result);
	}

	private void handleSnapshot(WsEvent event) {
		JsonNode payload = event.envelope().p();
		if (!validateRequired(event, payload, "roomId")) {
			return;
		}

		String roomId = eventMapper.text(payload, "roomId");

		RoomSubmitResult result = roomService.snapshot(
			roomId,
			event.senderSessionId(),
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
		if (!validateRequired(event, payload, "t")) {
			return;
		}

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
		sendRejected(
			event.senderSessionId(),
			event.wsNodeId(),
			1999,
			rejected.reason().name(),
			rejected.message()
		);
	}

	private void sendRejected(
		String sessionId,
		String wsNodeId,
		int errorEventCode,
		String reason,
		String message
	) {
		if (isBlank(sessionId)) {
			log.warn("event rejected without session target. reason={}, message={}", reason, message);
			return;
		}
		geEventPublisher.publish(
			wsNodeId,
			new GeEvent(
				sessionId,
				new KopicEnvelope(
					errorEventCode,
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

	private boolean validateRequired(WsEvent event, JsonNode payload, String... requiredFields) {
		try {
			eventMapper.require(payload, requiredFields);
			return true;
		} catch (IllegalArgumentException illegalArgumentException) {
			sendRejected(
				event.senderSessionId(),
				event.wsNodeId(),
				1999,
				"INVALID_REQUEST",
				illegalArgumentException.getMessage()
			);
			return false;
		}
	}

	private void handleGameStart(WsEvent event) {
		JsonNode payload = event.envelope().p();
		if (!validateRequired(event, payload, "roomId")) {
			return;
		}

		String roomId = eventMapper.text(payload, "roomId");

		roomService.startGame(
				roomId,
				event.senderSessionId()
			);

	}

	private void handleSetting(WsEvent event) {
		RoomSubmitResult result = roomService.updateSetting(
			event.roomId(),
			event.senderSessionId(),
			event.envelope().p()
		);
		emitResult(event, result);

	}
}
