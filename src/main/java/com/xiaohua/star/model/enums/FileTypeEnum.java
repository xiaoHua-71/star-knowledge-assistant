package com.xiaohua.star.model.enums;

/**
 * 文档类型（knowledge_document.fileType）
 *
 * <p>决定用哪个解析器：PDF→ApachePdfBox，DOCX→ApachePoi，TXT/MD→UTF-8 直读，
 * HTML/URL→jsoup 去标签。见 {@code ai/DocumentParserFactory}。
 */
public enum FileTypeEnum {

    PDF(".pdf"),
    DOCX(".docx"),
    TXT(".txt"),
    MD(".md"),
    HTML(".html", ".htm"),
    /**
     * 网页抓取，没有后缀，由「添加网页」接口显式指定
     */
    URL;

    private final String[] extensions;

    FileTypeEnum(String... extensions) {
        this.extensions = extensions;
    }

    /**
     * 按文件后缀（含点，小写）判定类型；不认识的后缀返回 null，由调用方报「不支持的文件类型」。
     */
    public static FileTypeEnum fromFileName(String fileName) {
        if (fileName == null) {
            return null;
        }
        String lower = fileName.toLowerCase();
        for (FileTypeEnum type : values()) {
            for (String ext : type.extensions) {
                if (lower.endsWith(ext)) {
                    return type;
                }
            }
        }
        return null;
    }
}
