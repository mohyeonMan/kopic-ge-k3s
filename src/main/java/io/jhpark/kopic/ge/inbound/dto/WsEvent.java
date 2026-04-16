package io.jhpark.kopic.ge.inbound.dto;

import io.jhpark.kopic.ge.common.dto.KopicEnvelope;

public record WsEvent(
    String senderSessionId,
    String wsNodeId,
    KopicEnvelope envelope,
    String sentAt
) {
}
