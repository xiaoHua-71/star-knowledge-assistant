package com.xiaohua.star.strategy.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.constant.CacheKey;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.mapper.UserMapper;
import com.xiaohua.star.model.dto.user.UserRegisterRequest;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.enums.UserRoleEnum;
import com.xiaohua.star.strategy.RegisterStrategy;
import com.xiaohua.star.utils.AvatarUtils;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class EmailRegisterStrategy implements RegisterStrategy {

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private PasswordEncoder passwordEncoder;

    @Override
    public Long register(UserRegisterRequest request) {
        String email = request.getEmail();
        String code = request.getCode();
        String password = request.getPassword();

        if (StringUtils.isAnyBlank(email, code, password)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱、验证码或密码不能为空");
        }
        if (!email.contains("@")) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱格式不正确");
        }
        if (password.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能小于8位");
        }

        String redisKey = CacheKey.EMAIL_CODE.key(email);
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);
        if (cachedCode == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码已过期，请重新获取");
        }
        if (!cachedCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }
        stringRedisTemplate.delete(redisKey);

        long count = userMapper.selectCount(new QueryWrapper<User>().eq("email", email));
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "该邮箱已注册");
        }

        User user = new User();
        user.setEmail(email);
        user.setUsername(email.split("@")[0]);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(email.split("@")[0]);
        user.setUserRole(UserRoleEnum.USER.getValue());
        if (userMapper.insert(user) <= 0) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户注册失败");
        }
        user.setAvatar(AvatarUtils.getDefaultAvatarByUserId(user.getId()));
        userMapper.updateById(user);
        return user.getId();
    }
}
