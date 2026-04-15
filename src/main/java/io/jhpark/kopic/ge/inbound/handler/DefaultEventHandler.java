package io.jhpark.kopic.ge.inbound.handler;

import com.fasterxml.jackson.databind.JsonNode;
import io.jhpark.kopic.ge.common.config.NodeProperties;
import io.jhpark.kopic.ge.room.service.RoomService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultEventHandler {

	private static final int DEFAULT_CAPACITY = 8;

	private final RoomService roomService;
	private final NodeProperties nodeProperties;

	public void handleJoin(String roomId, String sessionId, JsonNode payload) {
		String nickname = textValue(payload, "nickname");
		if (nickname == null || nickname.isBlank()) {
			log.warn("room join ignored missing nickname. roomId={}, sessionId={}", roomId, sessionId);
			return;
		}
		roomService.join(
			roomId,
			sessionId,
			nickname,
			textValue(payload, "wsNodeId", nodeProperties.nodeId()),
			textValue(payload, "ownerEngineId", nodeProperties.nodeId()),
			textValue(payload, "roomType", "PRIVATE"),
			intValue(payload, "capacity", DEFAULT_CAPACITY)
		);
	}

	public void handleLeave(String roomId, String sessionId) {
		roomService.leave(roomId, sessionId);
	}

	public void handleSnapshot(String roomId, String sessionId, String requestId) {
		roomService.snapshot(roomId, sessionId, requestId);
	}

	private String textValue(JsonNode payload, String fieldName) {
		return textValue(payload, fieldName, null);
	}

	private String textValue(JsonNode payload, String fieldName, String defaultValue) {
		if (payload == null || payload.isNull()) {
			return defaultValue;
		}
		String value = payload.path(fieldName).asText(null);
		return value == null || value.isBlank() ? defaultValue : value;
	}

	private int intValue(JsonNode payload, String fieldName, int defaultValue) {
		if (payload == null || payload.isNull() || !payload.path(fieldName).canConvertToInt()) {
			return defaultValue;
		}
		return payload.path(fieldName).asInt(defaultValue);
	}
}
