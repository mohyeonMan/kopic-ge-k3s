package io.jhpark.kopic.ge.common.util;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class CommonMapper {
    private final ObjectMapper objectMapper;

    public <T> T read(byte[] payload, Class<T> type) {
        try {
            return objectMapper.readValue(payload, type);
        } catch (Exception e) {
            log.error("JSON Conversion Failed : {} -> {}", payload, type);
            return null;
        }
    }

    public <T> T read(String payload, Class<T> type){
		try {
			return objectMapper.readValue(payload, type);
		} catch (Exception e) {
			log.error("JSON Conversion Failed : {} -> {}", payload, type);
			return null;
		}
	}

	public <T> T read(String payload, TypeReference<T> type){
		try {
			return objectMapper.readValue(payload, type);
		} catch (Exception e) {
			log.error("JSON Conversion Failed : {} -> {}", payload, type);
			return null;
		}
	}

    public JsonNode readTree(String payload) {
        try {
            return objectMapper.readTree(payload);
        } catch (Exception e) {
            log.error("JSON Conversion Failed : {} -> JsonNode", payload);
            return null;
        }
    }

    public String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("JSON Conversion Failed : {}", value);
            return null;
        }
    }

    public ObjectMapper rawMapper() {
        return objectMapper;
    }
}
