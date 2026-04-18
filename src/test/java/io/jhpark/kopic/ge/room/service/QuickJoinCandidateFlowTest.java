package io.jhpark.kopic.ge.room.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.room.dto.RoomSession;
import io.jhpark.kopic.ge.room.registry.InMemoryRoomSessionStore;
import io.jhpark.kopic.ge.room.registry.RoomSessionStore;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuickJoinCandidateFlowTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(3);

	private RoomSessionStore sessionStore;
	private ExecutorService executor;
	private ScheduledExecutorService scheduler;
	private RoomService roomService;

	@BeforeEach
	void setUp() {
		sessionStore = new InMemoryRoomSessionStore();
		executor = Executors.newSingleThreadExecutor();
		scheduler = Executors.newSingleThreadScheduledExecutor();

		RoomRunner roomRunner = new DefaultRoomRunner(sessionStore, executor, scheduler);
		CommonMapper commonMapper = new CommonMapper(new ObjectMapper());
		GeEventPublisher geEventPublisher = (wsNodeId, event) -> {
		};
		RoomJobFactory roomJobFactory = new DefaultRoomJobFactory(commonMapper, geEventPublisher);

		roomService = new DefaultRoomService(sessionStore, roomRunner, roomJobFactory);
	}

	@AfterEach
	void tearDown() {
		executor.shutdownNow();
		scheduler.shutdownNow();
	}

	@Test
	void fullRoomBecomesQuickJoinCandidateAfterLeave() {
		roomService.quickJoin("sid_1", "nick_1", "ws_1");

		String roomId = awaitCandidateRoomId();
		awaitParticipantCount(roomId, 1);

		roomService.quickJoin("sid_2", "nick_2", "ws_2");
		awaitParticipantCount(roomId, 2);
		awaitNoQuickJoinCandidate();

		roomService.leave(roomId, "sid_1", "ws_1");
		awaitParticipantCount(roomId, 1);

		assertEquals(roomId, awaitCandidateRoomId());
	}

	private String awaitCandidateRoomId() {
		Instant deadline = Instant.now().plus(TIMEOUT);
		while (Instant.now().isBefore(deadline)) {
			Optional<String> candidate = sessionStore.findFirstAvailableQuickRoomId();
			if (candidate.isPresent()) {
				return candidate.get();
			}
			sleep();
		}
		fail("Timed out waiting for quick join candidate");
		return null;
	}

	private void awaitNoQuickJoinCandidate() {
		Instant deadline = Instant.now().plus(TIMEOUT);
		while (Instant.now().isBefore(deadline)) {
			if (sessionStore.findFirstAvailableQuickRoomId().isEmpty()) {
				return;
			}
			sleep();
		}
		fail("Timed out waiting for quick join candidate list to become empty");
	}

	private void awaitParticipantCount(String roomId, int expectedCount) {
		Instant deadline = Instant.now().plus(TIMEOUT);
		while (Instant.now().isBefore(deadline)) {
			int participantCount = sessionStore.find(roomId)
				.map(RoomSession::getRoom)
				.map(room -> room.getParticipants().size())
				.orElse(-1);
			if (participantCount == expectedCount) {
				return;
			}
			sleep();
		}
		fail("Timed out waiting participant count. roomId=" + roomId + ", expected=" + expectedCount);
	}

	private void sleep() {
		try {
			Thread.sleep(10L);
		} catch (InterruptedException interruptedException) {
			Thread.currentThread().interrupt();
			fail("Interrupted while waiting");
		}
	}
}
