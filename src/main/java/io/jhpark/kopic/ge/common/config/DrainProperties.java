package io.jhpark.kopic.ge.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kopic.drain")
public record DrainProperties(
	Duration timeout,
	Duration pollInterval,
	Duration waitingRoomDeleteDelay,
	Duration inGameNotifyInterval
) {

	public DrainProperties {
		timeout = normalize(timeout, Duration.ofSeconds(3600));
		pollInterval = normalize(pollInterval, Duration.ofSeconds(60));
		waitingRoomDeleteDelay = normalize(waitingRoomDeleteDelay, Duration.ofSeconds(300));
		inGameNotifyInterval = normalize(inGameNotifyInterval, Duration.ofSeconds(180));
	}

	private static Duration normalize(Duration value, Duration defaultValue) {
		return value == null || value.isZero() || value.isNegative() ? defaultValue : value;
	}
}
