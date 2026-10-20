package com.xiaohua.star.agent.tool;

import com.xiaohua.star.agent.AgentToolPack;
import com.xiaohua.star.agent.ToolMeta;
import dev.langchain4j.agent.tool.Tool;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 只读工具：获取当前时间（见 knowledge/30）。
 *
 * <p>模型不知道自己「现在是几点」——训练数据有截止时间，运行时也无时钟。这类工具是补充模型
 * 缺失信息的最小例子，也是 M7 里最容易复现的演示（问「现在几点」必触发）。
 */
@Component
public class SystemTimeTool implements AgentToolPack {

    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String[] WEEKDAYS = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    @Tool("获取当前的日期和时间。当问题涉及『现在』『今天』『几号』『几点』等需要当前时刻的信息时调用。")
    @ToolMeta(displayName = "获取当前时间", sideEffect = false, source = "LOCAL")
    public String getCurrentTime() {
        ZonedDateTime now = ZonedDateTime.now();
        String weekday = WEEKDAYS[now.getDayOfWeek().getValue() - 1];
        return "当前时间：" + now.format(FORMAT) + "（" + weekday + "），时区 " + now.getZone();
    }
}
