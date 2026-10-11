package com.xiaohua.star.ai;

import com.xiaohua.star.config.MilvusProperties;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.ConnectParam;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.DropCollectionParam;
import io.milvus.param.collection.HasCollectionParam;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Milvus 集合的工厂与缓存。
 *
 * <p>本项目是<b>「每个知识库一个集合」</b>（{@code star_kb_{id}}），所以不能像单库那样
 * 声明一个全局 {@code EmbeddingStore} bean——集合名和维度在运行时才知道。
 *
 * <p>三个关键点：
 *
 * <ol>
 *   <li><b>客户端延迟初始化。</b>Milvus 没起时也要能启动应用（建知识库本身不碰 Milvus，
 *       等传第一个文档才需要）。所以客户端在第一次真正用到时才连，连不上给可读的错误。</li>
 *   <li><b>缓存 store。</b>构造 {@code MilvusEmbeddingStore} 会做 hasCollection + loadCollectionInMemory，
 *       每次都用新的会很慢，所以按集合名缓存。</li>
 *   <li><b>集合按需创建。</b>MilvusEmbeddingStore 构造时若集合不存在会自动建（schema 见
 *       {@code CollectionOperationsExecutor}：id 是 VarChar(36)，正好放得下我们的 vectorId）。</li>
 * </ol>
 */
@Slf4j
@Component
public class MilvusStoreFactory {

    private final MilvusProperties properties;

    /**
     * 共享客户端。volatile + 双检锁延迟初始化。
     */
    private volatile MilvusServiceClient client;

    private final Map<String, EmbeddingStore<TextSegment>> stores = new ConcurrentHashMap<>();

    public MilvusStoreFactory(MilvusProperties properties) {
        this.properties = properties;
    }

    /**
     * 取得（必要时创建）某个集合的向量库。
     *
     * @param collectionName 集合名，规则 star_kb_{id}
     * @param dimension      向量维度，必须与写入这些向量的模型一致
     */
    public EmbeddingStore<TextSegment> getStore(String collectionName, int dimension) {
        return stores.computeIfAbsent(collectionName, name -> {
            log.info("初始化 Milvus 集合: {}, dimension={}", name, dimension);
            return MilvusEmbeddingStore.builder()
                    .milvusClient(client())
                    .collectionName(name)
                    .dimension(dimension)
                    // 度量用 COSINE：向量已 L2 归一化，COSINE 与 IP 等价；
                    // 索引 FLAT 在当前数据量下够用（见 knowledge/21）
                    .metricType(MetricType.COSINE)
                    .indexType(IndexType.FLAT)
                    .build();
        });
    }

    /**
     * 集合是否存在。
     *
     * <p>删除向量前先问一句：{@link #getStore} 在集合不存在时会<b>创建</b>它，
     * 所以「删一个从没索引过的库的向量」如果直接 getStore，会凭空造出一个空集合。
     */
    public boolean collectionExists(String collectionName) {
        return client().hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(collectionName).build()).getData();
    }

    /**
     * 删除整个集合（删知识库 / 全量重建时用）。
     *
     * <p>显式用原生 SDK 而不是 {@code MilvusEmbeddingStore.dropCollection}：后者要先构造 store，
     * 而构造 store 会在集合不存在时把它建出来——副作用不对。
     */
    public void dropCollection(String collectionName) {
        stores.remove(collectionName);
        if (!collectionExists(collectionName)) {
            log.info("集合不存在，无需删除: {}", collectionName);
            return;
        }
        MilvusServiceClient c = client();
        c.dropCollection(DropCollectionParam.newBuilder().withCollectionName(collectionName).build());
        log.info("已删除 Milvus 集合: {}", collectionName);
    }

    private MilvusServiceClient client() {
        MilvusServiceClient local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    log.info("连接 Milvus {}:{}", properties.getHost(), properties.getPort());
                    local = new MilvusServiceClient(ConnectParam.newBuilder()
                            .withHost(properties.getHost())
                            .withPort(properties.getPort())
                            .build());
                    client = local;
                }
            }
        }
        return local;
    }

    @PreDestroy
    public void close() {
        if (client != null) {
            client.close();
        }
    }
}
