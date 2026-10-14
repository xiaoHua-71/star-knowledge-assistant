package com.xiaohua.star.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Agent 主循环的线程池。
 *
 * <p>一次运行的循环是<b>阻塞式</b>的（LC4j 的流式回调要桥接成阻塞等待，见 {@code AgentLoop}），
 * 不能占用 Servlet 请求线程——控制器返回 {@code SseEmitter} 后请求线程应立即释放、连接
 * 由别的线程喂事件。所以循环放在这里跑。
 *
 * <p>池子刻意开得小（一次运行的整个生命周期都占着一个线程）。用
 * {@link ThreadPoolExecutor.CallerRunsPolicy}：队列满时由提交线程自己跑（形成背压），
 * 总比默默丢弃好。将来要支持大量并发运行、或运行要能挂起（M8 的 {@code WAITING_APPROVAL}）
 * 时，这套「一运行一线程」的模型需要重新评估。
 */
@Configuration
public class AgentExecutorConfig {

    public static final String AGENT_EXECUTOR = "agentExecutor";

    @Bean(name = AGENT_EXECUTOR)
    public Executor agentExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agent-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
