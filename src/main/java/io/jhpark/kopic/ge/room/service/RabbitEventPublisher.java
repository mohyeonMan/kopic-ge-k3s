package io.jhpark.kopic.ge.room.service;

import io.jhpark.kopic.ge.common.config.RabbitProperties;
import io.jhpark.kopic.ge.common.metrics.GeMetrics;
import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.outbound.dto.GeEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitEventPublisher implements GeEventPublisher {

	private final RabbitTemplate rabbitTemplate;
	private final RabbitProperties rabbitProperties;
	private final CommonMapper commonMapper;
	private final GeMetrics geMetrics;

	@Override
	public void publish(String wsNodeId, GeEvent event) {
		if (isBlank(wsNodeId) || event == null || isBlank(event.targetSessionId()) || event.envelope() == null) {
			log.warn("skip event push due to missing target. wsNodeId={}, event={}", wsNodeId, event);
			geMetrics.increment(
				"kopic_ge_outbound_publish_failures_total",
				"reason",
				"INVALID_TARGET"
			);
			return;
		}

		String body = commonMapper.write(event);
		if (body == null) {
			log.warn("skip event push due to serialization failure. wsNodeId={}, event={}", wsNodeId, event);
			geMetrics.increment(
				"kopic_ge_outbound_publish_failures_total",
				"reason",
				"SERIALIZE_FAILED"
			);
			return;
		}

		String routingKey = rabbitProperties.outboundRoutingKey(wsNodeId);
		try {
			rabbitTemplate.convertAndSend(rabbitProperties.outboundExchange(), routingKey, body);
			geMetrics.increment(
				"kopic_ge_outbound_events_total",
				"event_code",
				String.valueOf(event.envelope().e())
			);
			log.debug("event pushed. wsNodeId={}, targetSessionId={}, eventCode={}, routingKey={}",
				wsNodeId, event.targetSessionId(), event.envelope().e(), routingKey);
		} catch (RuntimeException runtimeException) {
			geMetrics.increment(
				"kopic_ge_outbound_publish_failures_total",
				"reason",
				"PUBLISH_EXCEPTION"
			);
			log.error("failed to publish event. wsNodeId={}, targetSessionId={}, eventCode={}, routingKey={}",
				wsNodeId,
				event.targetSessionId(),
				event.envelope().e(),
				routingKey,
				runtimeException);
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}
}
