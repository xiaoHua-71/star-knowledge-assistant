package com.xiaohua.star.agent;

import com.xiaohua.star.service.AgentStateService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时的僵尸运行扫描（M8）。见 knowledge/12 坑 6。
 *
 * <p>进程崩溃/被杀后，某些 {@code agent_run} 会永远停在 {@code PENDING}/{@code RUNNING}，
 * 前端一直转圈。所以应用启动就绪后扫一次：
 * ① 非终态运行 → {@code SUSPENDED(SERVER_RESTART)}；
 * ② 「已打算执行副作用工具、但结果未知」的步骤 → {@code FAILED}（不能让恢复逻辑自作主张重跑）。
 *
 * <p><b>必须是 {@link ApplicationReadyEvent}</b>，不能放 {@code @PostConstruct}——那时数据库可能还没就绪。
 * 只在启动时跑一次。
 */
@Slf4j
@Component
public class RunRecoveryScanner {

    @Resource
    private AgentStateService agentStateService;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            int[] result = agentStateService.scanZombieRuns();
            if (result[0] > 0 || result[1] > 0) {
                log.warn("启动扫描：挂起 {} 个未结束的运行，标记 {} 个结果未知的副作用步骤为 FAILED",
                        result[0], result[1]);
            }
        } catch (Exception e) {
            // 扫描失败不该拖垮启动
            log.error("启动扫描失败", e);
        }
    }
}
