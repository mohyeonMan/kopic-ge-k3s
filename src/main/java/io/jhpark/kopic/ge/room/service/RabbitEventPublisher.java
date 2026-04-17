package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.config.RabbitProperties;
import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitEventPublisher implements OutboundBroadcaster {

	private final RabbitTemplate rabbitTemplate;
	private final RabbitProperties rabbitProperties;
	private final CommonMapper commonMapper;

	@Override
	public void send(String wsNodeId, GeEvent event) {
		if (isBlank(wsNodeId) || event == null || isBlank(event.targetSessionId()) || event.envelope() == null) {
			log.warn("skip event push due to missing target. wsNodeId={}, event={}", wsNodeId, event);
			return;
		}

		String body = commonMapper.write(event);
		if (body == null) {
			log.warn("skip event push due to serialization failure. wsNodeId={}, event={}", wsNodeId, event);
			return;
		}

		String routingKey = rabbitProperties.outboundRoutingKey(wsNodeId);
		rabbitTemplate.convertAndSend(rabbitProperties.outboundExchange(), routingKey, body);
		log.info("event pushed. wsNodeId={}, targetSessionId={}, eventCode={}, routingKey={}",
			wsNodeId, event.targetSessionId(), event.envelope().e(), routingKey);
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
