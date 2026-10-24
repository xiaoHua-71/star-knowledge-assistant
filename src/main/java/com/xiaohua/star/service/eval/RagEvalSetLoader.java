package com.xiaohua.star.service.eval;

import com.xiaohua.star.model.eval.EvalSet;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 评估集加载：优先读文件系统，文件不存在时回退到 classpath。
 *
 * <p>不用 Spring 的 {@code ClassPathResource}：评测集是「要反复改」的项目资产，
 * 开发时应直接读工作目录里的文件，改了立刻生效；打包后才走 classpath 兜底。</p>
 */
@Component
@Slf4j
public class RagEvalSetLoader {

    @Resource
    private RagEvalProperties properties;

    public String getEvalSetFile() {
        return properties.getEvalSetFile();
    }

    public EvalSet load() {
        String file = properties.getEvalSetFile();
        Path path = Paths.get(file);
        if (Files.exists(path)) {
            try {
                return EvalSet.parse(Files.readString(path, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalArgumentException("读取评估集失败 [" + path.toAbsolutePath() + "]: " + e.getMessage(), e);
            }
        }
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("rag-eval/eval-set.json")) {
            if (in == null) {
                throw new IllegalArgumentException("评估集不存在: " + path.toAbsolutePath()
                        + "（classpath rag-eval/eval-set.json 也没有）");
            }
            return EvalSet.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalArgumentException("读取 classpath 评估集失败: " + e.getMessage(), e);
        }
    }
}
