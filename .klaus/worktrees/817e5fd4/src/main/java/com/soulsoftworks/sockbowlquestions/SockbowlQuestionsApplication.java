package com.soulsoftworks.sockbowlquestions;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties
public class SockbowlQuestionsApplication {

	public static void main(String[] args) {
		SpringApplication.run(SockbowlQuestionsApplication.class, args);
	}

}
