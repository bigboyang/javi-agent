package com.agent.common.utils;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 보안상 캡처해서는 안 되는 민감한 HTTP 헤더를 필터링하는 유틸리티.
 */
public final class HeaderSanitizer {

    private static final Set<String> BLACKLIST = new HashSet<>(Arrays.asList(
            "authorization",
            "cookie",
            "set-cookie",
            "x-api-key",
            "proxy-authorization",
            "x-auth-token",
            "access_token",
            "refresh_token"
    ));

    /**
     * 헤더 이름이 민감한 정보인지 확인한다.
     *
     * @param headerName 헤더 이름
     * @return 민감한 정보면 true
     */
    public static boolean isSensitive(String headerName) {
        if (headerName == null) {
            return false;
        }
        return BLACKLIST.contains(headerName.toLowerCase());
    }

    private HeaderSanitizer() {}
}
