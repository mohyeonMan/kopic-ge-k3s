package io.jhpark.kopic.ge.inbound.listener;

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

	public void handle(WsEvent event) {
		log.info("received event from RabbitMQ. senderId={}, eventCode={}",
			event.senderId(), event.envelope().e());
	}

	@RabbitListener(
		queues = "#{@rabbitNodeQueue.name}",
		containerFactory = "rabbitListenerContainerFactory"
	)
	public void receive(String payload) {
		log.info("Received message from RabbitMQ: {}", payload);
		WsEvent event = commonMapper.read(payload, WsEvent.class);
		if (event == null) {
			log.warn("Dropping non-JSON or unmappable RabbitMQ payload: {}", payload);
			return;
		}
		handle(event);
	}
}
