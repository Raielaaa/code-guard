package com.repo.guard;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class GuardApplication {

	public static void main(String[] args) {
		SpringApplication.run(GuardApplication.class, args);
	}

}
