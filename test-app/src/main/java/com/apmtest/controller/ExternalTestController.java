package com.apmtest.controller;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/external")
public class ExternalTestController {

    private final StringRedisTemplate redisTemplate;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final RabbitTemplate rabbitTemplate;

    public ExternalTestController(StringRedisTemplate redisTemplate, KafkaTemplate<String, String> kafkaTemplate, RabbitTemplate rabbitTemplate) {
        this.redisTemplate = redisTemplate;
        this.kafkaTemplate = kafkaTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    // Redis 테스트: 키 저장 및 조회
    @GetMapping("/redis")
    public Map<String, String> testRedis(@RequestParam(defaultValue = "test-key") String key, 
                                         @RequestParam(defaultValue = "test-value") String value) {
        redisTemplate.opsForValue().set(key, value);
        String savedValue = redisTemplate.opsForValue().get(key);
        
        Map<String, String> result = new HashMap<>();
        result.put("key", key);
        result.put("value", savedValue);
        result.put("status", "success");
        return result;
    }

    // Kafka 테스트: 메시지 전송
    @GetMapping("/kafka/send")
    public String sendKafka(@RequestParam(defaultValue = "Hello Kafka!") String message) {
        kafkaTemplate.send("test-topic", message);
        return "Message sent to Kafka: " + message;
    }

    // Kafka 소비자: 메시지 수신 시 로그 출력 (에이전트 계측 확인용)
    @KafkaListener(topics = "test-topic", groupId = "test-group")
    public void listenKafka(String message) {
        System.out.println("Received message from Kafka: " + message);
        redisTemplate.opsForValue().set("last-kafka-msg", message);
    }

    // RabbitMQ 테스트: 메시지 전송
    @GetMapping("/rabbitmq/send")
    public String sendRabbitmq(@RequestParam(defaultValue = "Hello Rabbit!") String message) {
        rabbitTemplate.convertAndSend("test-queue", message);
        return "Message sent to RabbitMQ: " + message;
    }

    // RabbitMQ 소비자
    @RabbitListener(queuesToDeclare = @org.springframework.amqp.rabbit.annotation.Queue("test-queue"))
    public void listenRabbit(String message) {
        System.out.println("Received message from RabbitMQ: " + message);
        redisTemplate.opsForValue().set("last-rabbit-msg", message);
    }
}
