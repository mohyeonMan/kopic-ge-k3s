package io.jhpark.kopic.ge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling
@SpringBootApplication
public class KopicGeApplication {

	public static void main(String[] args) {
		SpringApplication.run(KopicGeApplication.class, args);
	}
}
