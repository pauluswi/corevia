package com.pswied.corevia;

import com.pswied.corevia.security.CoreviaSecurityProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(CoreviaSecurityProperties.class)
public class CoreviaApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoreviaApplication.class, args);
    }
}
