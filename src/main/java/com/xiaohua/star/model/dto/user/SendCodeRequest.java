package com.xiaohua.star.model.dto.user;

import java.io.Serializable;
import lombok.Data;

/**
 * 发送邮箱注册验证码请求体
 *
 * @author 小花
 * @from 好好学习
 */
@Data
public class SendCodeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 邮箱
     */
    private String email;
}
