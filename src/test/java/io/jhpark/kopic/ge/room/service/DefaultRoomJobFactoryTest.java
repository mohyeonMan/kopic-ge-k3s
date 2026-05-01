package io.jhpark.kopic.ge.room.service;

import static org.assertj.core.api.Assertions.assertThat;

import io.jhpark.kopic.ge.common.config.GameTimerProperties;
import io.jhpark.kopic.ge.common.config.JacksonConfig;
import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.common.util.TimeFormatUtil;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import io.jhpark.kopic.ge.room.dto.Participant;
import io.jhpark.kopic.ge.room.dto.Room;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DefaultRoomJobFactoryTest {

	@Test
	void joinAssignsUnusedColorIndexAndPublishesItInSnapshotAndJoinEvent() {
		CapturingGeEventPublisher geEventPublisher = new CapturingGeEventPublisher();
		DefaultRoomJobFactory roomJobFactory = new DefaultRoomJobFactory(
			new CommonMapper(new JacksonConfig().objectMapper()),
			geEventPublisher,
			new WordPoolProvider(),
			new GameTimerProperties(null, null, null, null, null, null, null)
		);
		Room room = new Room(Room.PRIVATE_ROOM_TYPE, "host-session");
		room.getParticipants().put(
			"host-session",
			new Participant("node-1", "host-session", "host", 1, TimeFormatUtil.now())
		);
		room.getParticipants().put(
			"guest-session",
			new Participant("node-2", "guest-session", "guest", 2, TimeFormatUtil.now())
		);

		roomJobFactory.join("new-session", "newbie", "node-3").action().apply(room);

		Participant joinedParticipant = room.getParticipants().get("new-session");
		assertThat(joinedParticipant).isNotNull();
		assertThat(joinedParticipant.colorIndex()).isBetween(1, 20).isNotIn(1, 2);

		JsonNode snapshotPayload = geEventPublisher.findPayload("new-session", 408);
		assertThat(snapshotPayload).isNotNull();
		assertThat(snapshotPayload.path("snap").path("participants").path("new-session").path("nickname").asText())
			.isEqualTo("newbie");
		assertThat(snapshotPayload.path("snap").path("participants").path("new-session").path("colorIndex").asInt())
			.isEqualTo(joinedParticipant.colorIndex());

		List<JsonNode> joinPayloads = geEventPublisher.findPayloads(301);
		assertThat(joinPayloads).hasSize(3);
		assertThat(joinPayloads)
			.allSatisfy(payload -> {
				assertThat(payload.path("sessionId").asText()).isEqualTo("new-session");
				assertThat(payload.path("nickname").asText()).isEqualTo("newbie");
				assertThat(payload.path("colorIndex").asInt()).isEqualTo(joinedParticipant.colorIndex());
			});
	}

	private static final class CapturingGeEventPublisher implements GeEventPublisher {

		private final List<PublishedEvent> events = new ArrayList<>();

		@Override
		public void publish(String wsNodeId, GeEvent event) {
			events.add(new PublishedEvent(wsNodeId, event));
		}

		private JsonNode findPayload(String targetSessionId, int eventCode) {
			return events.stream()
				.map(PublishedEvent::event)
				.filter(event -> event.targetSessionId().equals(targetSessionId))
				.filter(event -> event.envelope().e() == eventCode)
				.map(event -> event.envelope().p())
				.findFirst()
				.orElse(null);
		}

		private List<JsonNode> findPayloads(int eventCode) {
			return events.stream()
				.map(PublishedEvent::event)
				.filter(event -> event.envelope().e() == eventCode)
				.map(event -> event.envelope().p())
				.toList();
		}
	}

	private record PublishedEvent(
		String wsNodeId,
		GeEvent event
	) {
	}
}
