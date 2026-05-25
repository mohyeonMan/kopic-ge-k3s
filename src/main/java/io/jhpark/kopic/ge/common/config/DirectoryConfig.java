package io.jhpark.kopic.ge.common.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DirectoryProperties.class)
public class DirectoryConfig {
}
