package com.xiaohua.star.model.dto.user;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户注册请求体
 */
@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 3191241716373120793L;

    /**
     * 用户名
     */
    private String username;

    /**
     * 用户密码
     */
    private String password;

    /**
     * 确认密码
     */
    private String checkPassword;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 邮箱（邮箱注册时使用）
     */
    private String email;

    /**
     * 邮箱验证码（邮箱注册时使用）
     */
    private String code;

    /**
     * 注册方式（password / email）
     */
    private String registerType;
}
