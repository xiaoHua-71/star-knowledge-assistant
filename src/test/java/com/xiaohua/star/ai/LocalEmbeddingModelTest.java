package com.xiaohua.star.ai;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地向量模型的可用性验证（M0）。
 *
 * <p>这一组测试不启动 Spring 上下文，直接 new 模型 —— 因为要验的恰恰是「本地模型能不能脱离框架跑起来」。
 * 它守的是三条容易静默出错的假设：
 *
 * <ol>
 *   <li><b>ONNX 原生库能加载。</b>onnxruntime 的 native 库要解压到临时目录再加载，
 *       在缺 VC++ 运行库或架构不匹配的机器上会失败。失败信息通常很难懂，所以单独验一次。</li>
 *   <li><b>维度是 512。</b>{@code milvus.dimension} 必须和它一致，否则写入向量库时才报错，
 *       而那时错误信息里不会提到「你配置的维度写错了」。</li>
 *   <li><b>模型不会自动加检索前缀。</b>官方 Javadoc 建议给 query 加
 *       {@code 为这个句子生成表示以用于检索相关文章：} 前缀，但模型本身不这么做。
 *       这条断言就是防止「哪天官方改成自动加前缀了，我们代码里再加一次变成加两遍」。</li>
 * </ol>
 */
class LocalEmbeddingModelTest {

    /**
     * 官方 Javadoc 建议加在 query 上的检索前缀（document 侧不加）。
     */
    private static final String QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章：";

    @Test
    void 中文文本能向量化且维度为512() {
        EmbeddingModel model = new BgeSmallZhV15QuantizedEmbeddingModel();

        Embedding embedding = model.embed("你好").content();

        assertNotNull(embedding, "embed 返回了 null");
        assertEquals(512, embedding.dimension(), "维度必须和 milvus.dimension 配置一致");

        // 全零向量意味着模型加载了但没真正推理出结果 —— 只断言维度是抓不到这种情况的
        float[] vector = embedding.vector();
        boolean allZero = true;
        for (float v : vector) {
            if (v != 0f) {
                allZero = false;
                break;
            }
        }
        assertTrue(!allZero, "向量全为 0，说明模型没有真正推理");
    }

    @Test
    void 模型不会自动添加检索前缀() {
        EmbeddingModel model = new BgeSmallZhV15QuantizedEmbeddingModel();

        Embedding withoutPrefix = model.embed("年假怎么算").content();
        Embedding withPrefix = model.embed(QUERY_PREFIX + "年假怎么算").content();

        // 如果某天官方改成自动加前缀，这两个向量会变得完全相同，这条断言就会失败。
        // 那时要做的不是删掉断言，而是回头检查 EmbeddingEngine 有没有重复加前缀。
        assertNotEquals(withoutPrefix, withPrefix,
                "加了前缀和没加前缀的向量居然一样 —— 模型可能已经自动加前缀了，"
                        + "此时查询侧若再加一次就会变成加两遍，需要检查 EmbeddingEngine");
    }
}
