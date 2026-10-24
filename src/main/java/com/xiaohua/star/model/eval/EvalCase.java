package com.xiaohua.star.model.eval;

import java.util.List;

/**
 * 评估集里的一条用例：一个「用户会怎么问」的查询，加上它期望命中哪些文章。
 *
 * <p>期望来源写成路径片段即可（{@code database/mysql/mysql-index.html}），
 * 匹配规则见 {@code EvalSourceMatcher}。</p>
 *
 * @param id              用例标识，报告里用来指认是哪一条
 * @param enabled         是否参与本次评估；设为 false 可以临时屏蔽一条而不删掉它
 * @param query           查询文本，写成用户会问的自然问法
 * @param expectedSources 期望命中的来源，可以给多个
 * @param tags            方向标签，只用于报告分组阅读
 * @param note            备注，解释这条在考什么
 */
public record EvalCase(String id,
                       Boolean enabled,
                       String query,
                       List<String> expectedSources,
                       List<String> tags,
                       String note) {

    /** 未写 enabled 时默认启用 */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public List<String> tagsOrEmpty() {
        return tags == null ? List.of() : tags;
    }
}
