package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.room.dto.Room;
import java.time.Duration;
import java.time.Instant;

public interface RoomContext {

	Room room();

	String roomId();

	Instant now();

	void closeRoom();

	void after(Duration delay, RoomAction action);
}
