package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.RoomSession;
import java.time.Duration;
import java.util.function.Function;

public record RoomJob(
	String roomId,
	Function<RoomSession, Result> action
) {

	public RoomJob {
		if (roomId == null || roomId.isBlank()) {
			throw new IllegalArgumentException("roomId must not be blank");
		}
		if (action == null) {
			throw new IllegalArgumentException("action must not be null");
		}
	}

	public record Result(RoomJob nextJob, Timer timer, String cancelTimerKey, boolean closeIfEmpty) {

		private static final Result NONE = new Result(null, null, null, false);

		public Result {}

		public static Result none() {
			return NONE;
		}

		public static Result submit(RoomJob nextJob) {
			return nextJob == null ? NONE : new Result(nextJob, null, null, false);
		}

		public static Result schedule(String timerKey, Duration delay, RoomJob nextJob) {
			Timer timer = Timer.of(timerKey, delay, nextJob);
			return timer == null ? NONE : new Result(null, timer, null, false);
		}

		public static Result cancelTimer(String timerKey) {
			if (timerKey == null || timerKey.isBlank()) {
				return NONE;
			}
			return new Result(null, null, timerKey, false);
		}

		public static Result requestCloseIfEmpty() {
			return new Result(null, null, null, true);
		}
	}

	public record Timer(String timerKey, Duration delay, RoomJob nextJob) {

		public Timer {
			if (timerKey == null || timerKey.isBlank()) {
				throw new IllegalArgumentException("timerKey must not be blank");
			}
			if (delay == null || delay.isNegative()) {
				throw new IllegalArgumentException("delay must not be negative");
			}
			if (nextJob == null) {
				throw new IllegalArgumentException("nextJob must not be null");
			}
		}

		static Timer of(String timerKey, Duration delay, RoomJob nextJob) {
			if (timerKey == null || timerKey.isBlank() || delay == null || delay.isNegative() || nextJob == null) {
				return null;
			}
			return new Timer(timerKey, delay, nextJob);
		}
	}
}
