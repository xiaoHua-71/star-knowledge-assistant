package com.xiaohua.star.model.dto.tool;

import com.xiaohua.star.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 工具调用日志查询（{@code GET /tools/logs}，M12）。契约见 frontend/60。
 */
@EqualsAndHashCode(callSuper = true)
@Data
public class ToolLogQueryRequest extends PageRequest {

    /** 可选：只看某次运行 */
    private Long runId;

    /** 可选：按工具名筛选 */
    private String toolName;

    /** 可选：true 只看成功，false 只看失败 */
    private Boolean success;
}
