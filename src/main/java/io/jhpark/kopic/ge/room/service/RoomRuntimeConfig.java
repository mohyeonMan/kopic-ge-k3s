package io.jhpark.kopic.ge.room.service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RoomRuntimeConfig {

	@Bean(name = "roomRunnerExecutor", destroyMethod = "shutdown")
	public ExecutorService roomRunnerExecutor() {
		int workers = Math.max(2, Runtime.getRuntime().availableProcessors());
		return Executors.newFixedThreadPool(workers);
	}

	@Bean(name = "roomRunnerScheduler", destroyMethod = "shutdown")
	public ScheduledExecutorService roomRunnerScheduler() {
		return Executors.newScheduledThreadPool(2);
	}
}
