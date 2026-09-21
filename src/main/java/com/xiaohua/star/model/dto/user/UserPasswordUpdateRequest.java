package com.xiaohua.star.model.dto.user;

import java.io.Serializable;
import lombok.Data;

/**
 * 用户修改密码请求（仅修改当前登录用户自己）
 */
@Data
public class UserPasswordUpdateRequest implements Serializable {

    /**
     * 旧密码
     */
    private String oldPassword;

    /**
     * 新密码（长度 ≥ 8）
     */
    private String newPassword;

    private static final long serialVersionUID = 1L;
}
