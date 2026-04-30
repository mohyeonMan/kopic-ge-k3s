package io.jhpark.kopic.ge.common.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kopic.game-timer")
public record GameTimerProperties(
	Duration closeIfEmpty,
	Duration startRound,
	Duration nextTurn,
	Duration openWordChoice,
	Duration turnResult,
	Duration gameResult,
	Duration quickRestart
) {

	public GameTimerProperties {
		closeIfEmpty = normalize(closeIfEmpty, Duration.ofSeconds(30));
		startRound = normalize(startRound, Duration.ofSeconds(5));
		nextTurn = normalize(nextTurn, Duration.ofSeconds(5));
		openWordChoice = normalize(openWordChoice, Duration.ofSeconds(5));
		turnResult = normalize(turnResult, Duration.ofSeconds(7));
		gameResult = normalize(gameResult, Duration.ofSeconds(10));
		quickRestart = normalize(quickRestart, Duration.ofSeconds(5));
	}

	private static Duration normalize(Duration value, Duration defaultValue) {
		if (value == null || value.isZero() || value.isNegative()) {
			return defaultValue;
		}
		return value;
	}
}
