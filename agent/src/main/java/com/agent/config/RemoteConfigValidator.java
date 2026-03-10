package com.agent.config;

import com.agent.logs.AgentLogger;

/**
 * 원격 설정의 유효성을 검증하는 유틸리티.
 */
public final class RemoteConfigValidator {

    /**
     * 설정값이 안전한 범위 내에 있는지 확인한다.
     *
     * @param config 검증할 설정
     * @return 유효하면 true, 아니면 false
     */
    public static boolean isValid(RemoteConfig config) {
        if (config == null) return false;

        boolean valid = true;

        // 1. 샘플링 비율 (0.0 ~ 1.0)
        if (config.getHeadSampleRate() < 0.0 || config.getHeadSampleRate() > 1.0) {
            AgentLogger.warn("[RemoteConfig] 검증 실패: headSampleRate는 0.0에서 1.0 사이여야 함: " + config.getHeadSampleRate());
            valid = false;
        }

        // 2. TPS (음수 불가)
        if (config.getTargetTps() < 0) {
            AgentLogger.warn("[RemoteConfig] 검증 실패: targetTps는 음수일 수 없음: " + config.getTargetTps());
            valid = false;
        }

        // 3. 배치 사이즈 (너무 크면 메모리 이슈)
        if (config.getBatchSize() <= 0 || config.getBatchSize() > 10000) {
            AgentLogger.warn("[RemoteConfig] 검증 실패: batchSize 범위 초과 (1~10000): " + config.getBatchSize());
            valid = false;
        }

        return valid;
    }

    private RemoteConfigValidator() {}
}
