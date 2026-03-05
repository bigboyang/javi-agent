package com.agent.span;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AttributeKeyTest {

    @Test
    void sameKeyAndTypeShouldBeEqual() {
        AttributeKey<String> k1 = AttributeKey.stringKey("host");
        AttributeKey<String> k2 = AttributeKey.stringKey("host");
        assertEquals(k1, k2);
        assertEquals(k1.hashCode(), k2.hashCode());
    }

    @Test
    void differentTypeShouldNotBeEqual() {
        AttributeKey<String> str = AttributeKey.stringKey("x");
        AttributeKey<Long>   lng = AttributeKey.longKey("x");
        assertNotEquals(str, lng);
    }

    @Test
    void differentKeyShouldNotBeEqual() {
        assertNotEquals(AttributeKey.stringKey("a"), AttributeKey.stringKey("b"));
    }

    @Test
    void mapOverwriteWorksCorrectly() {
        // 핵심 버그 수정 검증: equals/hashCode 없을 때 덮어쓰기 불가 문제
        Map<AttributeKey<?>, Object> attrs = new HashMap<>();
        AttributeKey<String> key = AttributeKey.stringKey("db.url");

        attrs.put(key, "localhost");
        attrs.put(AttributeKey.stringKey("db.url"), "prod-db"); // 다른 인스턴스

        assertEquals(1, attrs.size(), "같은 키는 덮어써야 함 — 2개가 되면 안 됨");
        assertEquals("prod-db", attrs.get(AttributeKey.stringKey("db.url")));
    }

    @Test
    void allTypesHaveCorrectType() {
        assertEquals(AttributeKey.Type.STRING,  AttributeKey.stringKey("k").getType());
        assertEquals(AttributeKey.Type.LONG,    AttributeKey.longKey("k").getType());
        assertEquals(AttributeKey.Type.DOUBLE,  AttributeKey.doubleKey("k").getType());
        assertEquals(AttributeKey.Type.BOOLEAN, AttributeKey.booleanKey("k").getType());
    }
}
