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

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");
    private static final Pattern STRING_PATTERN = Pattern.compile("'[^']*'");
    private static final Pattern HEX_PATTERN = Pattern.compile("\\b0x[0-9a-fA-F]+\\b");

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

        String result = sql;
        
        // 1. 문자열 리터럴 마스킹 ('value' -> ?)
        result = STRING_PATTERN.matcher(result).replaceAll("?");
        
        // 2. 숫자 리터럴 마스킹 (123 -> ?)
        // 주의: 식별자에 포함된 숫자는 제외해야 함. 여기서는 단순화하여 공백/연산자 사이의 숫자만 처리.
        result = NUMBER_PATTERN.matcher(result).replaceAll("?");
        
        // 3. 16진수 리터럴 마스킹 (0xabc -> ?)
        result = HEX_PATTERN.matcher(result).replaceAll("?");

        return result;
    }

    private SqlSanitizer() {}
}
