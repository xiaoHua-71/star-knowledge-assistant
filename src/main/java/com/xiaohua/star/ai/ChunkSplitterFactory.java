package com.xiaohua.star.ai;

import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.exception.BusinessException;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.splitter.DocumentByCharacterSplitter;
import dev.langchain4j.data.document.splitter.DocumentByLineSplitter;
import dev.langchain4j.data.document.splitter.DocumentByParagraphSplitter;
import dev.langchain4j.data.document.splitter.DocumentByRegexSplitter;
import dev.langchain4j.data.document.splitter.DocumentByWordSplitter;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 切片器：把文档正文切成检索单元。
 *
 * <p>用官方的分层拆分器拼一条链，但做了两处<b>脱离官方默认</b>的改动，都是因为中文：
 *
 * <ol>
 *   <li><b>末级必须是字符级，不能直接用官方 {@code DocumentSplitters.recursive}。</b>
 *       官方链的末级是「按词拆」（{@code DocumentByWordSplitter}），而「词」是按空白切的。
 *       中文没有空格，一个 800 字的无标点中文段落会被当成<b>一个词</b>；这个词超过
 *       maxSegmentSize 时，word 拆分器发现自己没有下级（subSplitter=null），直接抛
 *       {@code RuntimeException: ... and there is no subSplitter defined}。
 *       所以我们在 word 之后接一个字符拆分器兜底。</li>
 *   <li><b>「句」这一级用正则，不用官方的 {@code DocumentBySentenceSplitter}。</b>
 *       后者内部是 OpenNLP 的<b>英文</b>断句模型（{@code SentenceDetectorME}），
 *       对中文「。」「！」基本不切，等于中文文档没有句子边界，全被当成按字符硬切。</li>
 * </ol>
 *
 * <p>⚠️ <b>已知限制：中文场景 chunkOverlap 实际不生效。</b>重叠区域由
 * {@code HierarchicalDocumentSplitter.overlapFrom()} 计算，而它写死了用 OpenNLP 英文断句模型
 * （{@code getOverlapSentenceSplitter()} 返回的是 {@code DocumentBySentenceSplitter}，
 * 子类改不了）。中文句子切不出来 → 取不到整句 → 重叠为空。这条待 M3 评测集量化后再决定
 * 是否自研重叠逻辑，见 knowledge/22 坑 2。
 */
@Component
public class ChunkSplitterFactory {

    /**
     * 句末标点的零宽断言：在这些字符之后切开，且<b>保留它们</b>（只做零宽断言，不消费字符），
     * 这样配合 joinDelimiter="" 能原样拼回，不丢空格。
     */
    private static final String SENTENCE_END_REGEX = "(?<=[。！？!?；;])";

    /**
     * 建一个「段落 → 行 → 句 → 词 → 字符」的拆分器。
     *
     * @param maxChars     单个切片最大字符数（knowledge_base.chunkSize）
     * @param overlapChars 相邻切片重叠字符数（knowledge_base.chunkOverlap），必须小于 maxChars。
     *                     注意：中文下该值目前不生效，见类注释。
     */
    public DocumentSplitter create(int maxChars, int overlapChars) {
        if (maxChars <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chunkSize 必须大于 0");
        }
        if (overlapChars < 0 || overlapChars >= maxChars) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "chunkOverlap 必须在 [0, chunkSize) 之间");
        }
        DocumentSplitter characterLevel = new DocumentByCharacterSplitter(maxChars, overlapChars);
        DocumentSplitter wordLevel = new DocumentByWordSplitter(maxChars, overlapChars, characterLevel);
        // 句：用中文标点正则，joinDelimiter 用空串（中文不靠空格连接，空串能原样拼接）
        DocumentSplitter sentenceLevel = new DocumentByRegexSplitter(
                SENTENCE_END_REGEX, "", maxChars, overlapChars, wordLevel);
        DocumentSplitter lineLevel = new DocumentByLineSplitter(maxChars, overlapChars, sentenceLevel);
        return new DocumentByParagraphSplitter(maxChars, overlapChars, lineLevel);
    }

    /**
     * 便捷方法：直接切文本。
     */
    public List<TextSegment> split(String text, int maxChars, int overlapChars) {
        return create(maxChars, overlapChars).split(Document.from(text));
    }
}
