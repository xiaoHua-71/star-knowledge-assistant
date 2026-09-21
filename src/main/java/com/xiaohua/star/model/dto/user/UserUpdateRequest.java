package com.xiaohua.star.model.dto.user;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户更新请求（仅更新当前登录用户自己，不含密码）
 */
@Data
public class UserUpdateRequest implements Serializable {

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * 用户头像URL
     */
    private String avatar;

    private static final long serialVersionUID = 1L;
}
