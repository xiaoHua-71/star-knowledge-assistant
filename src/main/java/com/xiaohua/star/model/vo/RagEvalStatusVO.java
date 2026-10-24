package com.xiaohua.star.model.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 离线评估的当前状态（供 HTTP 轮询）。
 *
 * <p>状态：IDLE / PREPARING（导入语料）/ RUNNING / SUCCESS / FAILED。</p>
 */
@Data
public class RagEvalStatusVO implements Serializable {

    private String state;

    private String message;

    private Long startTime;

    private int totalVariants;

    private int doneVariants;

    private int totalCases;

    private int doneCases;

    private String reportPath;

    private static final long serialVersionUID = 1L;

    public static RagEvalStatusVO idle() {
        RagEvalStatusVO vo = new RagEvalStatusVO();
        vo.setState("IDLE");
        vo.setMessage("尚未运行");
        return vo;
    }

    public static RagEvalStatusVO of(String state, String message) {
        RagEvalStatusVO vo = new RagEvalStatusVO();
        vo.setState(state);
        vo.setMessage(message);
        vo.setStartTime(System.currentTimeMillis());
        return vo;
    }
}
