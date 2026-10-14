package com.pswied.corevia.events;

public interface EventPublisher {

    void publish(String topic, String key, String payload);
}
