package com.xiaohua.star.service;

import com.baomidou.mybatisplus.extension.service.IService;

import com.xiaohua.star.model.dto.user.UserLoginRequest;
import com.xiaohua.star.model.dto.user.UserPasswordUpdateRequest;
import com.xiaohua.star.model.dto.user.UserRegisterRequest;
import com.xiaohua.star.model.dto.user.UserUpdateRequest;
import com.xiaohua.star.model.entity.User;
import com.xiaohua.star.model.vo.UserVO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户服务
 */
public interface UserService extends IService<User> {

    /**
     * 用户注册
     *
     * @param userRegisterRequest 用户注册请求体
     * @return 新用户 id
     */
    Long userRegister(UserRegisterRequest userRegisterRequest);

    /**
     * 发送邮箱注册验证码
     *
     * @param email 邮箱
     */
    void sendRegisterCode(String email);

    /**
     * 用户登录
     *
     * @param userLoginRequest 用户登录请求体
     * @param request          请求对象
     * @return 脱敏后的用户信息
     */
    UserVO userLogin(UserLoginRequest userLoginRequest, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request 请求对象
     * @return 用户信息
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 获取当前登录用户（未登录返回 null，不抛异常）
     *
     * @param request 请求对象
     * @return 用户信息，未登录时为 null
     */
    User getLoginUserOrNull(HttpServletRequest request);

    /**
     * 获取脱敏的已登录用户信息
     *
     * @return 脱敏用户信息
     */
    UserVO getLoginUserVO(User user);

    /**
     * 用户注销
     *
     * @param request 请求对象
     * @return 是否成功
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 更新当前登录用户自己的信息（昵称、头像）
     *
     * @param userUpdateRequest 更新请求体
     * @param request           请求对象
     * @return 更新后的脱敏用户信息
     */
    UserVO updateUser(UserUpdateRequest userUpdateRequest, HttpServletRequest request);

    /**
     * 修改当前登录用户密码（需校验旧密码）
     *
     * @param passwordUpdateRequest 修改密码请求体
     * @param request               请求对象
     * @return 是否成功
     */
    boolean updatePassword(UserPasswordUpdateRequest passwordUpdateRequest, HttpServletRequest request);

}

