package io.jhpark.kopic.ge.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kopic.redis")
public record KopicRedisProperties(
	boolean enabled,
	Duration heartbeatTtl,
	Duration roomCodeTtl,
	Duration heartbeatInterval,
	Duration loadInterval,
	Duration reconciliationInterval,
	Duration initialDelay,
	double roomWeight,
	Keys keys
) {

	public KopicRedisProperties {
		heartbeatTtl = heartbeatTtl == null ? Duration.ofSeconds(15) : heartbeatTtl;
		roomCodeTtl = roomCodeTtl == null ? Duration.ofHours(6) : roomCodeTtl;
		heartbeatInterval = normalize(heartbeatInterval, Duration.ofSeconds(10));
		loadInterval = normalize(loadInterval, Duration.ofMinutes(1));
		reconciliationInterval = normalize(reconciliationInterval, Duration.ofHours(1));
		initialDelay = initialDelay == null || initialDelay.isNegative() ? Duration.ZERO : initialDelay;
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

	private static Duration normalize(Duration value, Duration defaultValue) {
		return value == null || value.isZero() || value.isNegative() ? defaultValue : value;
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
