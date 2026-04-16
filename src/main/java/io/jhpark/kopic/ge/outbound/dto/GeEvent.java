package io.jhpark.kopic.ge.outbound.dto;

import io.jhpark.kopic.ge.common.dto.KopicEnvelope;

public record GeEvent(
	String targetSessionId,
	KopicEnvelope envelope,
	String sentAt
) {
}
