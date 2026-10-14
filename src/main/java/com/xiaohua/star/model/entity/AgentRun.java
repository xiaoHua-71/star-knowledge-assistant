package com.xiaohua.star.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一次 Agent 运行（用户提问 → 得到回答）。见 knowledge/10 3.3、knowledge/12。
 *
 * <p><b>没有 {@code isDelete}</b>：这是审计数据，永不删除。
 */
@TableName(value = "agent_run")
@Data
public class AgentRun implements Serializable {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long conversationId;

    private Long userId;

    /** {@link com.xiaohua.star.model.enums.AgentRunStatusEnum} 的 name */
    private String status;

    /** {@link com.xiaohua.star.model.enums.InterruptTypeEnum} 的 name，未挂起时为 null */
    private String interruptType;

    /** 当前轮次 */
    private Integer stepNo;

    /** 最大轮次护栏（创建时由配置写入） */
    private Integer maxRounds;

    /** todo 计划版本号（计划功能未实现，恒 0） */
    private Integer planVersion;

    /** 触发本次运行的用户消息 id */
    private Long inputMessageId;

    /** 最终助手消息 id */
    private Long outputMessageId;

    private String errorMsg;

    /** 累计输入 token */
    private Integer tokenIn;

    /** 累计输出 token */
    private Integer tokenOut;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private static final long serialVersionUID = 1L;
}
