package io.jhpark.kopic.ge.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kopic.directory")
public record DirectoryProperties(
	boolean enabled,
	String status,
	Duration heartbeatTtl,
	Duration roomCodeTtl,
	long heartbeatIntervalMs,
	long loadIntervalMs,
	long reconciliationIntervalMs,
	long initialDelayMs,
	double roomWeight,
	Keys keys
) {

	public DirectoryProperties {
		status = normalize(status, "ACTIVE");
		heartbeatTtl = heartbeatTtl == null ? Duration.ofSeconds(15) : heartbeatTtl;
		roomCodeTtl = roomCodeTtl == null ? Duration.ofHours(6) : roomCodeTtl;
		heartbeatIntervalMs = heartbeatIntervalMs <= 0 ? 5000 : heartbeatIntervalMs;
		loadIntervalMs = loadIntervalMs <= 0 ? 5000 : loadIntervalMs;
		reconciliationIntervalMs = reconciliationIntervalMs <= 0 ? 3600000 : reconciliationIntervalMs;
		initialDelayMs = Math.max(0, initialDelayMs);
		roomWeight = roomWeight <= 0 ? 2.0 : roomWeight;
		keys = keys == null ? new Keys(null, null, null, null) : keys;
	}

	public String geKey(String geId) {
		return keys.gePrefix() + geId;
	}

	public String roomCodeKey(String roomCode) {
		return keys.roomCodePrefix() + roomCode;
	}

	private static String normalize(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value.trim();
	}

	public record Keys(
		String gePrefix,
		String geLoad,
		String quickAvailable,
		String roomCodePrefix
	) {

		public Keys {
			gePrefix = normalize(gePrefix, "ge:");
			geLoad = normalize(geLoad, "ge:load");
			quickAvailable = normalize(quickAvailable, "quick:available");
			roomCodePrefix = normalize(roomCodePrefix, "roomcode:");
		}
	}
}
