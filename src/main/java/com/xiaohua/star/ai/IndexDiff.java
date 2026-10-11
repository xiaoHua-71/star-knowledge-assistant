package com.xiaohua.star.ai;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 切片集合的增量 diff（见 knowledge/24）。
 *
 * <p>增量索引的核心：拿「旧的 vectorId 集合」和「新的 vectorId 集合」比，
 * 只嵌入新增、只删除消失、交集原样保留。
 *
 * <p>之所以能这么做，是因为 vectorId 是**确定性**的：
 * {@code UUID(docId + "#" + chunkIndex + "#" + MD5(content))}。
 * 所以「id 相同」等价于「同一段内容在同一位置」——id 相同即未变，判据是精确的，
 * 不需要再回头比较文本。
 *
 * <p>抽成无状态纯函数，是为了能脱离 Spring/Milvus 单测（见 {@code IndexDiffTest}）。
 */
public final class IndexDiff {

    private IndexDiff() {
    }

    /**
     * @param toInsert  新出现、要嵌入并写入的 id，<b>保持传入的 newIds 顺序</b>
     *                  （顺序要和 embedding 结果一一对应）
     * @param toDelete  已消失、要从 Milvus 与 MySQL 删除的 id
     * @param unchanged 两边都有的 id 个数（原样保留，不碰）
     */
    public record Result(List<String> toInsert, List<String> toDelete, int unchanged) {

        /**
         * 是否有变化。false 表示这篇文档的切片集合与上次完全一致 —— 可以整体跳过。
         */
        public boolean noChange() {
            return toInsert.isEmpty() && toDelete.isEmpty();
        }
    }

    public static Result diff(Collection<String> oldIds, List<String> newIds) {
        Set<String> oldSet = oldIds == null ? Set.of() : new HashSet<>(oldIds);
        List<String> newList = newIds == null ? List.of() : newIds;
        Set<String> newSet = new LinkedHashSet<>(newList);

        List<String> toInsert = newList.stream().filter(id -> !oldSet.contains(id)).toList();
        List<String> toDelete = oldSet.stream().filter(id -> !newSet.contains(id)).toList();
        int unchanged = (int) newList.stream().filter(oldSet::contains).count();
        return new Result(toInsert, toDelete, unchanged);
    }
}
