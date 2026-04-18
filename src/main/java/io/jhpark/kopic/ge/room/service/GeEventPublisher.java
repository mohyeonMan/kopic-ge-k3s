package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.outbound.dto.GeEvent;

public interface GeEventPublisher {

	void publish(String wsNodeId, GeEvent event);

}
