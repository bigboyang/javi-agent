package com.agent.common.utils;

import java.util.regex.Pattern;

/**
 * SQL 쿼리에서 민감한 리터럴 값을 마스킹 처리하는 유틸리티.
 *
 * <p>예:
 * <ul>
 *   <li>{@code SELECT * FROM users WHERE id = 123} -> {@code SELECT * FROM users WHERE id = ?}</li>
 *   <li>{@code INSERT INTO logs VALUES ('secret')} -> {@code INSERT INTO logs VALUES (?)}</li>
 * </ul>
 */
public final class SqlSanitizer {

    /**
     * 단일 얼터네이션 패턴 — SQL을 한 번만 스캔하여 3종 리터럴을 모두 치환.
     * 순서: 문자열 리터럴 우선(내부 숫자 오치환 방지) → 16진수 → 10진수.
     */
    private static final Pattern LITERAL_PATTERN =
            Pattern.compile("'[^']*'|\\b0x[0-9a-fA-F]+\\b|\\b\\d+\\b");

    /**
     * SQL 쿼리를 마스킹 처리한다.
     *
     * @param sql 원본 SQL
     * @return 마스킹된 SQL
     */
    public static String sanitize(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        return LITERAL_PATTERN.matcher(sql).replaceAll("?");
    }

    private SqlSanitizer() {}
}
