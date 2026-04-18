package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.Room;
import java.time.Duration;
import java.util.function.Function;

public record RoomJob(
	Function<Room, FollowUpResult> action
) {

	public RoomJob {
		if (action == null) {
			throw new IllegalArgumentException("action must not be null");
		}
	}

	public record FollowUpResult(
		FollowUp followUp,
		String cancelTimerKey,
		FollowUpAction followUpAction
	) {

		private static final FollowUpResult NONE =
			new FollowUpResult(null, null, FollowUpAction.NONE);

		public FollowUpResult {
			if (followUpAction == null) {
				followUpAction = FollowUpAction.NONE;
			}
		}

		public static FollowUpResult none() {
			return NONE;
		}

		public static FollowUpResult followUp(RoomJob nextJob, Duration delay, String timerKey) {
			FollowUp followUp = FollowUp.of(nextJob, delay, timerKey);
			return followUp == null
				? NONE
				: new FollowUpResult(followUp, null, FollowUpAction.NONE);
		}

		public static FollowUpResult cancelTimer(String timerKey) {
			if (timerKey == null || timerKey.isBlank()) {
				return NONE;
			}
			return new FollowUpResult(null, timerKey, FollowUpAction.NONE);
		}

		public static FollowUpResult requestCloseIfEmpty() {
			return new FollowUpResult(null, null, FollowUpAction.REQUEST_CLOSE_IF_EMPTY);
		}
	}

	public enum FollowUpAction {
		NONE,
		REQUEST_CLOSE_IF_EMPTY,
		ADD_QUICK_JOIN_CANDIDATE,
		REMOVE_QUICK_JOIN_CANDIDATE
	}

	public record FollowUp(RoomJob nextJob, Duration delay, String timerKey) {

		public FollowUp {
			if (nextJob == null) {
				throw new IllegalArgumentException("nextJob must not be null");
			}
			if (delay == null) {
				if (timerKey != null && !timerKey.isBlank()) {
					throw new IllegalArgumentException("timerKey requires delay");
				}
			} else {
				if (delay.isNegative()) {
					throw new IllegalArgumentException("delay must not be negative");
				}
				if (timerKey == null || timerKey.isBlank()) {
					throw new IllegalArgumentException("timerKey must not be blank when delayed");
				}
			}
		}

		public boolean delayed() {
			return delay != null;
		}

		static FollowUp of(RoomJob nextJob, Duration delay, String timerKey) {
			if (nextJob == null) {
				return null;
			}
			if (delay == null) {
				if (timerKey != null && !timerKey.isBlank()) {
					return null;
				}
				return new FollowUp(nextJob, null, null);
			}
			if (delay.isNegative() || timerKey == null || timerKey.isBlank()) {
				return null;
			}
			return new FollowUp(nextJob, delay, timerKey);
		}
	}
}
