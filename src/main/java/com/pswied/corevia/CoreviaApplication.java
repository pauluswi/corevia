package com.pswied.corevia;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class CoreviaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreviaApplication.class, args);
    }
}
