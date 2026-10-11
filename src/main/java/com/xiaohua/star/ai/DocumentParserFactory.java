package com.xiaohua.star.ai;

import com.xiaohua.star.common.ErrorCode;
import com.xiaohua.star.exception.BusinessException;
import com.xiaohua.star.model.enums.FileTypeEnum;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentParser;
import dev.langchain4j.data.document.parser.apache.pdfbox.ApachePdfBoxDocumentParser;
import dev.langchain4j.data.document.parser.apache.poi.ApachePoiDocumentParser;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 文档解析：把各种格式的原始内容变成纯文本 {@link Document}。
 *
 * <p>官方只提供 PDF（{@code langchain4j-document-parser-apache-pdfbox}）和
 * Office（{@code -apache-poi}）两个 parser <b>模块</b>；TXT/MD 没有专门模块，
 * 直接按 UTF-8 读即可，HTML/网页用 jsoup 去标签（见 knowledge/20 的选型表）。
 *
 * <p>解析出来的正文是后续切片、向量化的输入。<b>正文质量直接决定检索质量</b>，
 * 但这一层只负责「拿到文本」，不做清洗（页码、页眉这类噪声的去除放在后续增量里评估）。
 */
@Component
public class DocumentParserFactory {

    /**
     * 官方 parser 都是无状态的，可复用单例。
     */
    private static final DocumentParser PDF_PARSER = new ApachePdfBoxDocumentParser();
    private static final DocumentParser OFFICE_PARSER = new ApachePoiDocumentParser();

    /**
     * 网页抓取超时（毫秒）。抓取失败在「添加网页」接口里会转成文档 status=FAILED。
     */
    private static final int URL_TIMEOUT_MS = 10_000;

    /**
     * 按文件类型解析<b>文件</b>（URL 类型走 {@link #parseUrl}）。
     */
    public Document parseFile(FileTypeEnum fileType, InputStream inputStream) {
        try {
            return switch (fileType) {
                case PDF -> PDF_PARSER.parse(inputStream);
                case DOCX -> OFFICE_PARSER.parse(inputStream);
                case TXT, MD -> Document.from(readUtf8(inputStream));
                case HTML -> Document.from(htmlToText(readUtf8(inputStream)));
                case URL -> throw new BusinessException(ErrorCode.PARAMS_ERROR, "URL 类型请走 parseUrl");
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "文档解析失败: " + e.getMessage());
        }
    }

    /**
     * 抓取网页并提取正文（去掉导航、脚本、样式等噪声）。
     */
    public Document parseUrl(String url) {
        try {
            org.jsoup.nodes.Document html = Jsoup.connect(url)
                    .timeout(URL_TIMEOUT_MS)
                    .userAgent("Mozilla/5.0 (compatible; StarKnowledgeAssistant/1.0)")
                    .get();
            String text = htmlToText(html);
            if (text.isBlank()) {
                throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页正文为空: " + url);
            }
            return Document.from(text);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "网页抓取失败: " + e.getMessage());
        }
    }

    /**
     * jsoup 的 {@code body().text()} 已自动排除 script/style，并把块级元素之间补上换行语义的空白。
     */
    private static String htmlToText(String html) {
        return htmlToText(Jsoup.parse(html));
    }

    private static String htmlToText(org.jsoup.nodes.Document html) {
        return html.body() == null ? "" : html.body().text();
    }

    private static String readUtf8(InputStream inputStream) throws IOException {
        return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
    }
}
