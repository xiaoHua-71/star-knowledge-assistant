package com.xiaohua.star.config;

import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.model.scoring.ScoringModel;
import dev.langchain4j.model.scoring.onnx.OnnxScoringModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AI 模型装配（向量模型 + 重排模型）
 *
 * <p>对话模型不在这里——它在这两个之外单独接（见 {@link ChatModelConfig} 与 knowledge/01）。
 *
 * <p><b>向量模型（embedding）</b>：本地 ONNX，模型文件打进 jar，零下载零调用成本。
 * 它是单例、线程安全，全进程复用一个即可（{@code AbstractInProcessEmbeddingModel}
 * 内部用线程池并行化）。维度 512，实测见 {@code LocalEmbeddingModelTest}。
 *
 * <p><b>重排模型（rerank）</b>：官方不打包模型文件，要自己准备 ONNX + tokenizer，
 * 所以做成开关（{@code ai.rerank.enabled=false} 默认关）。开启时若文件缺失，
 * 这里<b>启动就抛异常</b>——而不是拖到第一次 rerank 才报错（这是 M0 坑 1 的教训，
 * 见 knowledge/21）。
 */
@Slf4j
@Configuration
public class AiModelConfig {

    @Bean
    public EmbeddingModel embeddingModel() {
        log.info("加载本地向量模型 bge-small-zh-v1.5-q（512 维，ONNX 进程内）");
        return new BgeSmallZhV15QuantizedEmbeddingModel();
    }

    /**
     * 本地重排模型。仅当 {@code ai.rerank.enabled=true} 时创建。
     *
     * <p>之所以用工厂方法而不是直接 new Bean：模型文件路径要校验存在性，
     * 缺失时给出可读的错误信息（否则用户只会看到一句 ONNX 的原生库报错）。
     */
    @Bean
    @ConditionalOnProperty(name = "ai.rerank.enabled", havingValue = "true")
    public ScoringModel onnxScoringModel(@Value("${ai.rerank.model-path}") String modelPath,
                                         @Value("${ai.rerank.tokenizer-path}") String tokenizerPath) {
        requireExists(modelPath, "rerank 模型文件");
        requireExists(tokenizerPath, "rerank tokenizer 文件");
        log.info("加载本地重排模型: model={}, tokenizer={}", modelPath, tokenizerPath);
        return new OnnxScoringModel(modelPath, sessionOptions(), tokenizerPath);
    }

    /**
     * ONNX Runtime 会话选项。
     *
     * <p><b>为什么要关内存池</b>：ORT 默认用 BFC 内存池并做内存模式预分配，会一次性预留
     * 一大块连续内存。在本机这种「16G 内存被 IDE + Docker Milvus 占掉大半」的环境里，
     * 加载/推理时会直接抛 {@code Failed to allocate ... BFCArena::AllocateRawInternal}（实测踩到）。
     * 关掉内存池与内存模式预分配，改为按需分配，峰值内存明显下降——代价是少量性能（本场景可忽略）。
     */
    private static OrtSession.SessionOptions sessionOptions() {
        try {
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setCPUArenaAllocator(false);
            options.setMemoryPatternOptimization(false);
            // 关掉图优化：ORT 默认会把模型重写成「优化后的副本」，等于在内存里多存一份模型。
            // 内存紧的机器上这一步常常就是压垮骆驼的那根稻草（本机实测）。代价是推理稍慢。
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT);
            return options;
        } catch (OrtException e) {
            throw new IllegalStateException("构造 ONNX SessionOptions 失败: " + e.getMessage(), e);
        }
    }

    private static void requireExists(String path, String what) {
        if (!Files.exists(Path.of(path))) {
            throw new IllegalStateException(what + "不存在: " + path
                    + "。rerank 模型文件需单独下载（见 knowledge/21），或把 ai.rerank.enabled 设为 false");
        }
    }
}
