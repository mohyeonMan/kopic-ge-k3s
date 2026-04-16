package io.jhpark.kopic.ge.room.service;

public interface RoomRunner {

	RoomSubmitResult submit(String roomId, RoomJob job, WsSessionMeta meta);
}
