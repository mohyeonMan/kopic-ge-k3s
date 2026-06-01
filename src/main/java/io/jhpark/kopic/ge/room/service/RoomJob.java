package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.Room;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
		List<FollowUp> followUps,
		String cancelTimerKey,
		FollowUpAction followUpAction
	) {

		private static final FollowUpResult NONE =
			new FollowUpResult(List.of(), null, FollowUpAction.NONE);

		public FollowUpResult {
			if (followUps == null || followUps.isEmpty()) {
				followUps = List.of();
			} else {
				List<FollowUp> sanitized = new ArrayList<>();
				for (FollowUp followUp : followUps) {
					if (followUp != null) {
						sanitized.add(followUp);
					}
				}
				followUps = sanitized.isEmpty() ? List.of() : List.copyOf(sanitized);
			}
			if (followUpAction == null) {
				followUpAction = FollowUpAction.NONE;
			}
		}

		public FollowUpResult(FollowUp followUp, String cancelTimerKey, FollowUpAction followUpAction) {
			this(
				followUp == null ? List.of() : List.of(followUp),
				cancelTimerKey,
				followUpAction
			);
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

		public static FollowUpResult followUps(List<FollowUp> followUps, String cancelTimerKey) {
			return new FollowUpResult(followUps, cancelTimerKey, FollowUpAction.NONE);
		}

		public static FollowUpResult cancelTimer(String timerKey) {
			if (timerKey == null || timerKey.isBlank()) {
				return NONE;
			}
			return new FollowUpResult(List.of(), timerKey, FollowUpAction.NONE);
		}

		public static FollowUpResult requestCloseIfEmpty() {
			return new FollowUpResult(List.of(), null, FollowUpAction.REQUEST_CLOSE_IF_EMPTY);
		}

		public static FollowUpResult requestClose() {
			return new FollowUpResult(List.of(), null, FollowUpAction.REQUEST_CLOSE);
		}
	}

	public enum FollowUpAction {
		NONE,
		REQUEST_CLOSE_IF_EMPTY,
		REQUEST_CLOSE,
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
