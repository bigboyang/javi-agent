package com.agent.common.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * URL 쿼리 파라미터에서 민감한 값을 마스킹하는 유틸리티.
 *
 * <p>예:
 * <ul>
 *   <li>{@code /search?q=hello&token=abc123} → {@code /search?q=hello&token=[REDACTED]}</li>
 *   <li>{@code /api?password=secret&page=1} → {@code /api?password=[REDACTED]&page=1}</li>
 * </ul>
 *
 * <p>활성화 제어: {@code JAVI_PII_MASKING_ENABLED=true} (기본값 true).
 */
public final class UrlSanitizer {

    private static final boolean ENABLED;

    static {
        String val = System.getenv("JAVI_PII_MASKING_ENABLED");
        if (val == null) val = System.getProperty("javi.pii.masking.enabled", "true");
        ENABLED = !"false".equalsIgnoreCase(val);
    }

    private static final Set<String> SENSITIVE_PARAMS = new HashSet<>(Arrays.asList(
            "password", "passwd", "pass", "pwd",
            "token", "api_key", "apikey", "api_token", "apitoken",
            "secret", "client_secret",
            "access_token", "refresh_token",
            "auth", "authorization",
            "ssn", "social_security",
            "card", "card_number", "card_num", "cardnumber",
            "cvv", "cvv2", "cvc",
            "pin", "credit_card", "pan",
            "private_key", "private-key",
            "x-api-key", "x_api_key"
    ));

    private static final String REDACTED = "[REDACTED]";

    /**
     * URL의 쿼리 파라미터 중 민감한 키의 값을 {@code [REDACTED]}로 대체한다.
     *
     * @param url 원본 URL (절대·상대 모두 가능)
     * @return 마스킹된 URL; {@code JAVI_PII_MASKING_ENABLED=false}이면 원본 반환
     */
    public static String sanitize(String url) {
        if (!ENABLED || url == null || url.isEmpty()) return url;

        int queryStart = url.indexOf('?');
        if (queryStart < 0) return url;

        String base = url.substring(0, queryStart);
        String rest  = url.substring(queryStart + 1);

        // fragment 분리
        int fragIdx = rest.indexOf('#');
        String fragment = "";
        if (fragIdx >= 0) {
            fragment = rest.substring(fragIdx);
            rest     = rest.substring(0, fragIdx);
        }

        if (rest.isEmpty()) return url;

        StringBuilder sb = new StringBuilder(url.length());
        sb.append(base).append('?');

        String[] pairs = rest.split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) sb.append('&');
            String pair = pairs[i];
            int eq = pair.indexOf('=');
            if (eq >= 0) {
                String key = pair.substring(0, eq);
                if (SENSITIVE_PARAMS.contains(key.toLowerCase())) {
                    sb.append(key).append('=').append(REDACTED);
                } else {
                    sb.append(pair);
                }
            } else {
                sb.append(pair);
            }
        }

        sb.append(fragment);
        return sb.toString();
    }

    private UrlSanitizer() {}
}
