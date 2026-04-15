package io.jhpark.kopic.ge.room.service;

@FunctionalInterface
public interface RoomAction {

	void run(RoomContext context);
}
