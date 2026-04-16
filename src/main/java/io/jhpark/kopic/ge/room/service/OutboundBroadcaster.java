package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.outbound.dto.GeEvent;

public interface OutboundBroadcaster {

	void send(String wsNodeId, GeEvent event);
}
