package com.xiaohua.star.common;

import java.io.Serializable;
import lombok.Data;

/**
 * 删除请求
 *
 * @author 小花
 * @from 好好学习
 */
@Data
public class DeleteRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    private static final long serialVersionUID = 1L;
}