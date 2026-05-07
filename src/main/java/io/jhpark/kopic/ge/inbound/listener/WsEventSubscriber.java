package io.jhpark.kopic.ge.inbound.listener;

import io.jhpark.kopic.ge.common.metrics.GeMetrics;
import io.jhpark.kopic.ge.inbound.handler.DefaultEventHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import io.jhpark.kopic.ge.common.util.CommonMapper;
import io.jhpark.kopic.ge.inbound.dto.WsEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class WsEventSubscriber {

	private final CommonMapper commonMapper;
	private final DefaultEventHandler eventHandler;
	private final GeMetrics geMetrics;

	public void handle(WsEvent event) {
		geMetrics.increment(
			"kopic_ge_inbound_events_total",
			"event_code",
			event != null && event.envelope() != null ? String.valueOf(event.envelope().e()) : null
		);
		log.debug("received event from RabbitMQ. senderId={}, eventCode={}",
			event != null ? event.senderSessionId() : null,
			event != null && event.envelope() != null ? event.envelope().e() : null);
		eventHandler.handle(event);
	}

	@RabbitListener(
		queues = "#{@rabbitNodeQueue.name}",
		containerFactory = "rabbitListenerContainerFactory"
	)
	public void receive(String payload) {
		log.debug("Received message from RabbitMQ: {}", payload);
		WsEvent event = commonMapper.read(payload, WsEvent.class);
		if (event == null) {
			log.warn("Dropping non-JSON or unmappable RabbitMQ payload: {}", payload);
			geMetrics.increment(
				"kopic_ge_inbound_rejected_total",
				"reason",
				"DESERIALIZE_FAILED"
			);
			return;
		}
		handle(event);
	}
}
