package io.jhpark.kopic.ge.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "kopic.rabbit")
public record RabbitProperties(
	String outboundExchange,
	String inboundQueuePrefix,
	String inboundRoutingKeyPrefix
) {

	public RabbitProperties {
		outboundExchange = normalize(outboundExchange, "kopic.ws.exchange");
		inboundQueuePrefix = normalize(inboundQueuePrefix, "kopic.ge.event.");
		inboundRoutingKeyPrefix = normalize(inboundRoutingKeyPrefix, "kopic.ge.route.");
	}

	private static String normalize(String value, String defaultValue) {
		return value == null || value.isBlank() ? defaultValue : value;
	}

	public String queueName(String nodeId) {
		return inboundQueuePrefix + nodeId;
	}

	public String routingKey(String nodeId) {
		return inboundRoutingKeyPrefix + nodeId;
	}
}
