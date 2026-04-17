package io.jhpark.kopic.ge.common.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record KopicEnvelope(
    int e,
    JsonNode p
    // String rid
) {
}
