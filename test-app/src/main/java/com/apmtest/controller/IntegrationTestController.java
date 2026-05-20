package com.apmtest.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/test")
public class IntegrationTestController {

    @Autowired(required = false)
    private StringRedisTemplate redisTemplate;

    @Autowired(required = false)
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired(required = false)
    private RabbitTemplate rabbitTemplate;

    // 1. Redis Test (Lettuce)
    @GetMapping("/redis/set")
    public String redisSet(@RequestParam String key, @RequestParam String value) {
        if (redisTemplate == null) return "Redis unavailable (e2e mode)";
        redisTemplate.opsForValue().set(key, value);
        return "Redis Set: " + key + "=" + value;
    }

    @GetMapping("/redis/get")
    public String redisGet(@RequestParam String key) {
        if (redisTemplate == null) return "Redis unavailable (e2e mode)";
        String val = redisTemplate.opsForValue().get(key);
        return "Redis Get: " + key + "=" + val;
    }

    // 2. Kafka Test (Producer & Consumer)
    @GetMapping("/kafka/send")
    public String kafkaSend(@RequestParam String topic, @RequestParam String message) {
        if (kafkaTemplate == null) return "Kafka unavailable (e2e mode)";
        kafkaTemplate.send(topic, message);
        return "Kafka Sent: " + message + " to topic " + topic;
    }

    @KafkaListener(topics = "test-topic", groupId = "test-group")
    public void listenKafka(String message) {
        System.out.println(">>> Received Kafka Message: " + message);
        // 에이전트가 이 컨슈머의 처리를 추적하는지 확인합니다.
    }

    // 3. RabbitMQ Test (Producer & Consumer)
    @GetMapping("/rabbitmq/send")
    public String rabbitSend(@RequestParam String queue, @RequestParam String message) {
        if (rabbitTemplate == null) return "RabbitMQ unavailable (e2e mode)";
        rabbitTemplate.convertAndSend(queue, message);
        return "RabbitMQ Sent: " + message + " to queue " + queue;
    }

    @RabbitListener(queues = "test-queue")
    public void listenRabbit(String message) {
        System.out.println(">>> Received RabbitMQ Message: " + message);
        // RabbitMQ 컨슈머도 동일하게 추적되는지 확인합니다.
    }

    // 4. 복합 테스트 (Chain Test)
    @GetMapping("/chain")
    public Map<String, Object> complexChain() {
        Map<String, Object> result = new HashMap<>();

        if (redisTemplate != null) {
            redisTemplate.opsForValue().set("last_call", String.valueOf(System.currentTimeMillis()));
            result.put("redis", "ok");
        } else {
            result.put("redis", "unavailable");
        }

        if (kafkaTemplate != null) {
            kafkaTemplate.send("test-topic", "Chain message from integration test");
            result.put("kafka", "sent");
        } else {
            result.put("kafka", "unavailable");
        }

        return result;
    }
}
