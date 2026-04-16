package io.jhpark.kopic.ge.room.service;

public interface RoomRunner {

	RoomSubmitResult submit(RoomJob job, RoomJobMeta meta);
}
