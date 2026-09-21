package com.xiaohua.star.strategy;

import com.xiaohua.star.model.dto.user.UserRegisterRequest;

public interface RegisterStrategy {

    Long register(UserRegisterRequest request);
}
