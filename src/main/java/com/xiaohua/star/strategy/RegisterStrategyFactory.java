package com.xiaohua.star.strategy;

import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RegisterStrategyFactory {

    @Resource
    private RegisterStrategy passwordRegisterStrategy;

    @Resource
    private RegisterStrategy emailRegisterStrategy;

    private final Map<String, RegisterStrategy> strategyMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        strategyMap.put("password", passwordRegisterStrategy);
        strategyMap.put("email", emailRegisterStrategy);
    }

    public RegisterStrategy getStrategy(String registerType) {
        RegisterStrategy strategy = strategyMap.get(registerType);
        if (strategy == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的注册方式: " + registerType);
        }
        return strategy;
    }
}
