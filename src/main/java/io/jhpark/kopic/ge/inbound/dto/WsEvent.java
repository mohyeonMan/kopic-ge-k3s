package io.jhpark.kopic.ge.inbound.dto;

import io.jhpark.kopic.ge.common.dto.KopicEnvelope;

public record WsEvent(
    String senderId,
    KopicEnvelope envelope,
    String sentAt
) {
}
