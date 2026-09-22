package com.cyk.command;

import com.cyk.bean.Message;
import com.cyk.bean.Session;
import com.cyk.constant.Constants;
import com.cyk.manager.SessionManager;
import picocli.CommandLine;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 列出所有已持久化的历史会话（{@code sk-hermes sessions}）。
 *
 * <p>只读取会话文件，不构建 Agent、不触发任何模型调用或后台线程。</p>
 */
@CommandLine.Command(name = "sessions", description = "列出历史会话，可用于 resume 恢复")
public class SessionsCommand implements Callable<Integer> {

    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @Override
    public Integer call() {
        SessionManager manager = new SessionManager(Constants.getHermesHome());
        List<Session> sessions = manager.listSessions();

        if (sessions.isEmpty()) {
            System.out.println("暂无历史会话。运行 `sk-hermes chat` 开始对话后，会话会自动记录。");
            return 0;
        }

        System.out.printf("共 %d 个会话（按最近活跃排序）：%n%n", sessions.size());
        System.out.printf("%-3s %-16s %-20s %-6s %s%n", "#", "会话ID", "最近活跃", "消息数", "最近一条用户消息");
        System.out.println("-".repeat(90));

        int index = 1;
        for (Session s : sessions) {
            System.out.printf("%-3d %-16s %-20s %-6d %s%n",
                    index++,
                    s.id,
                    TIME_FMT.format(Instant.ofEpochMilli(s.lastActivity)),
                    s.messages.size(),
                    lastUserMessagePreview(s));
        }

        System.out.println();
        System.out.println("恢复某个会话：sk-hermes resume <会话ID>");
        return 0;
    }

    /**
     * 取该会话最后一条用户消息，截断为预览。
     */
    private static String lastUserMessagePreview(Session session) {
        for (int i = session.messages.size() - 1; i >= 0; i--) {
            Message msg = session.messages.get(i);
            if ("user".equals(msg.role()) && msg.content() != null) {
                String content = msg.content().replaceAll("\\s+", " ").trim();
                return content.length() > 40 ? content.substring(0, 40) + "…" : content;
            }
        }
        return "";
    }
}
