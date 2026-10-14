package com.pswied.corevia.events;

import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Primary
@Profile("!kafka")
public class MockEventPublisher implements EventPublisher {

    @Override
    public void publish(String topic, String key, String payload) {
        System.out.println("[Mock Kafka] topic=" + topic + ", key=" + key + ", payload=" + payload);
    }
}
