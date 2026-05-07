package io.jhpark.kopic.ge.common.metrics;

import io.jhpark.kopic.ge.room.dto.Room;
import io.jhpark.kopic.ge.room.registry.RoomSessionStore;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class GeMetrics {

	private static final String UNKNOWN = "unknown";

	private final MeterRegistry meterRegistry;

	public GeMetrics(MeterRegistry meterRegistry, RoomSessionStore roomSessionStore) {
		this.meterRegistry = meterRegistry;
		registerRoomGauges(roomSessionStore);
	}

	public void increment(String metricName, String... rawLabelPairs) {
		increment(metricName, 1.0, rawLabelPairs);
	}

	public void increment(String metricName, double amount, String... rawLabelPairs) {
		if (metricName == null || metricName.isBlank() || amount <= 0) {
			return;
		}
		String[] tags = normalizeRawTags(rawLabelPairs);
		meterRegistry.counter(metricName, tags).increment(amount);
	}

	public void recordDuration(String metricName, long durationNanos, String... rawLabelPairs) {
		if (metricName == null || metricName.isBlank() || durationNanos < 0) {
			return;
		}
		String[] tags = normalizeRawTags(rawLabelPairs);
		meterRegistry.timer(metricName, tags).record(durationNanos, TimeUnit.NANOSECONDS);
	}

	private void registerRoomGauges(RoomSessionStore roomSessionStore) {
		Gauge.builder(
			"kopic_ge_rooms_active",
			() -> roomSessionStore.countActiveRoomsByType(Room.QUICK_ROOM_TYPE)
		)
			.tag("room_type", "quick")
			.description("number of active quick rooms")
			.register(meterRegistry);
		Gauge.builder(
			"kopic_ge_rooms_active",
			() -> roomSessionStore.countActiveRoomsByType(Room.PRIVATE_ROOM_TYPE)
		)
			.tag("room_type", "private")
			.description("number of active private rooms")
			.register(meterRegistry);
		Gauge.builder(
			"kopic_ge_participants_total",
			() -> roomSessionStore.countParticipantsByType(Room.QUICK_ROOM_TYPE)
		)
			.tag("room_type", "quick")
			.description("number of participants in quick rooms")
			.register(meterRegistry);
		Gauge.builder(
			"kopic_ge_participants_total",
			() -> roomSessionStore.countParticipantsByType(Room.PRIVATE_ROOM_TYPE)
		)
			.tag("room_type", "private")
			.description("number of participants in private rooms")
			.register(meterRegistry);
	}

	private String[] normalizeRawTags(String... rawLabelPairs) {
		if (rawLabelPairs == null || rawLabelPairs.length == 0) {
			return new String[0];
		}
		ArrayList<String> tags = new ArrayList<>(rawLabelPairs.length);
		for (int index = 0; index + 1 < rawLabelPairs.length; index += 2) {
			String key = rawLabelPairs[index];
			String value = rawLabelPairs[index + 1];
			if (key == null || key.isBlank()) {
				continue;
			}
			tags.add(key.trim());
			tags.add(normalizeRawLabelValue(value));
		}
		return tags.toArray(new String[0]);
	}

	private String normalizeRawLabelValue(String value) {
		if (value == null || value.isBlank()) {
			return UNKNOWN;
		}
		return value.trim();
	}
}
