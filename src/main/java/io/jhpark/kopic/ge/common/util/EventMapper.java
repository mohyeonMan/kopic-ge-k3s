package io.jhpark.kopic.ge.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class EventMapper extends CommonMapper {

	public EventMapper(ObjectMapper objectMapper) {
		super(objectMapper);
	}

	public JsonNode parse(String payload) {
		if (isBlank(payload)) {
			return null;
		}
		return readTree(payload);
	}

	public JsonNode parse(String payload, String... requiredFields) {
		JsonNode node = parse(payload);
		require(node, requiredFields);
		return node;
	}

	public void require(JsonNode payload, String... requiredFields) {
		if (requiredFields == null || requiredFields.length == 0) {
			return;
		}

		List<String> missing = new ArrayList<>();
		for (String rawField : requiredFields) {
			String field = normalizeField(rawField);
			if (field == null) {
				continue;
			}
			if (missingField(payload, field)) {
				missing.add(field);
			}
		}

		if (!missing.isEmpty()) {
			throw new IllegalArgumentException("missing required fields: " + String.join(", ", missing));
		}
	}

	private boolean missingField(JsonNode payload, String fieldName) {
		if (payload == null || payload.isNull() || payload.isMissingNode()) {
			return true;
		}

		JsonNode field = payload.path(fieldName);
		if (field == null || field.isNull() || field.isMissingNode()) {
			return true;
		}

		if (field.isTextual()) {
			return isBlank(field.asText(""));
		}

		if (field.isArray() || field.isObject()) {
			return field.isEmpty();
		}

		return false;
	}

	private String normalizeField(String fieldName) {
		if (isBlank(fieldName)) {
			return null;
		}
		return fieldName.trim();
	}

	public String text(JsonNode payload, String fieldName) {
		if (payload == null || isBlank(fieldName)) {
			return null;
		}
		String value = payload.path(fieldName).asText(null);
		return isBlank(value) ? null : value;
	}

	public int intValue(JsonNode payload, String fieldName, int defaultValue) {
		if (payload == null || isBlank(fieldName)) {
			return defaultValue;
		}
		JsonNode field = payload.path(fieldName);
		if (!field.canConvertToInt()) {
			return defaultValue;
		}
		int value = field.asInt(defaultValue);
		return value <= 0 ? defaultValue : value;
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
