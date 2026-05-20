package com.apmtest.config;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * 외부 서비스(Redis, Kafka, RabbitMQ) 없이 테스트를 실행하기 위한 Mock 빈 설정.
 * @MockBean 방식으로 Spring Boot test context가 직접 mock을 관리한다.
 */
@Configuration
public class MockExternalServicesConfig {

    @MockBean
    public StringRedisTemplate stringRedisTemplate;

    @MockBean
    @SuppressWarnings("rawtypes")
    public KafkaTemplate kafkaTemplate;

    @MockBean
    public RabbitTemplate rabbitTemplate;
}
