package com.xiaohua.star.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 知识库索引的异步线程池。
 *
 * <p>索引（解析 + 向量化）可能耗秒级，不能让 HTTP 请求线程等着，所以放这里异步跑。
 *
 * <p>用 {@link ThreadPoolExecutor.CallerRunsPolicy}：队列满时由提交线程自己跑，形成背压，
 * 总比默默丢弃任务（用户看到文档永远 PENDING）好。
 */
@Configuration
public class KbExecutorConfig {

    public static final String KB_INDEX_EXECUTOR = "kbIndexExecutor";

    @Bean(name = KB_INDEX_EXECUTOR)
    public Executor kbIndexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("kb-index-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
