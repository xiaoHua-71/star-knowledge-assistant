package com.xiaohua.star.agent.tool;

import com.xiaohua.star.agent.AgentToolPack;
import com.xiaohua.star.agent.RunContext;
import com.xiaohua.star.agent.ToolMeta;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 副作用工具示例：创建支持工单（M10）。见 knowledge/30、knowledge/12。
 *
 * <p>它是本项目第一个 {@code sideEffect = true} 的工具，用来演示「人工确认 → 恰好执行一次」：
 * 模型要调它时，运行会先挂到 {@code WAITING_APPROVAL}，用户同意后才真正执行。
 *
 * <p><b>为什么 {@code idempotentBackend = false}</b>：这个演示工具的后端（内存 Map）并不支持
 * 传入幂等键去重，所以必须靠 {@code requireApproval = true} 兜底——否则启动校验会拒绝它
 * （knowledge/12：既不幂等又不确认的副作用工具是定时炸弹）。
 *
 * <p>⚠️ 工单存在内存里，重启即丢。生产实现应换成真实的工单服务，并通过幂等键做后端去重。
 */
@Slf4j
@Component
public class CreateTicketTool implements AgentToolPack {

    private final AtomicLong seq = new AtomicLong(1000);
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();

    public record Ticket(String id, String title, String assignee, Long userId,
                         Long conversationId, LocalDateTime createTime) {
    }

    @Tool("创建一个支持工单（会真正写入工单系统，属于有副作用的操作）。"
            + "仅当用户明确要求建工单/提单/登记问题时调用。调用前应先向用户确认工单标题与处理人。")
    @ToolMeta(displayName = "创建工单", sideEffect = true, requireApproval = true,
            idempotentBackend = false, source = "INTERNAL")
    public String createTicket(
            @P(name = "title", description = "工单标题，简洁描述问题") String title,
            @P(name = "assignee", description = "处理人或处理组，例如 hr、it、finance") String assignee,
            @ToolMemoryId RunContext ctx) {
        String id = "T-" + seq.incrementAndGet();
        Ticket ticket = new Ticket(id, title, assignee, ctx.userId(), ctx.conversationId(),
                LocalDateTime.now());
        tickets.put(id, ticket);
        log.info("工单已创建: id={}, title={}, assignee={}, userId={}, conversationId={}",
                id, title, assignee, ctx.userId(), ctx.conversationId());
        return "工单创建成功：编号 " + id + "，标题「" + title + "」，处理人 " + assignee + "。";
    }

    /** 仅供测试/演示观察副作用次数。 */
    public int createdCount() {
        return tickets.size();
    }
}
