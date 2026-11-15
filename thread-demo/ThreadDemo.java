import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 演示 TrajectoryCollector 里的线程模型：
 * 1. 守护线程 + BlockingQueue
 * 2. 如何查看当前进程里的线程
 * 3. take() 阻塞 / interrupt 唤醒
 * 4. daemon 线程随主线程退出而消失
 */
public class ThreadDemo {

    // 和 TrajectoryCollector 一样的队列
    private static final BlockingQueue<String> queue = new LinkedBlockingQueue<>();

    // 和 TrajectoryCollector 一样的 volatile 标志
    private static volatile boolean running = true;

    public static void main(String[] args) throws Exception {
        System.out.println("=== 主线程启动: " + Thread.currentThread().getName() + " ===");

        // ---- 1. 启动后台守护线程（对应 startBackgroundProcessing）----
        Thread worker = new Thread(ThreadDemo::processLoop);
        worker.setDaemon(true); // 守护线程：主线程结束它自动结束
        worker.setName("TrajectoryCollector-ProcessingThread"); // 和项目同名，方便对照
        worker.start();

        // ---- 2. 查看当前进程所有线程 ----
        printAllThreads("刚启动后台线程时");

        // ---- 3. 往队列扔数据，观察后台线程被唤醒消费 ----
        for (int i = 1; i <= 3; i++) {
            queue.offer("轨迹样本-" + i);
            System.out.println("[主线程] 放入样本-" + i + "，队列剩余: " + queue.size());
            Thread.sleep(300); // 留时间让后台线程消费
        }

        // ---- 4. 队列为空时，后台线程会卡在 take() 上 ----
        Thread.sleep(200);
        printThreadState("TrajectoryCollector-ProcessingThread",
            "队列为空时（注意状态是 WAITING，卡在 take()）");

        // ---- 5. 用 interrupt 打断 take()（对应 shutdown）----
        System.out.println("\n=== 调用 shutdown：interrupt 打断 take() ===");
        running = false;
        worker.interrupt();
        worker.join(1000); // 等后台线程真正结束

        printThreadState("TrajectoryCollector-ProcessingThread",
            "shutdown 之后（线程应已消失）");

        System.out.println("\n=== 主线程结束。若是守护线程，JVM 会直接退出 ===");
    }

    /** 和 processLoop 一模一样的结构 */
    private static void processLoop() {
        while (running) {
            try {
                String entry = queue.take(); // 队列空就阻塞在这里，不耗 CPU
                System.out.println("  [后台线程] 取出并处理: " + entry);
            } catch (InterruptedException e) {
                System.out.println("  [后台线程] 被 interrupt 唤醒，退出循环");
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /** 打印所有线程：名称、是否守护、状态 */
    private static void printAllThreads(String title) {
        System.out.println("\n--- 当前所有线程 (" + title + ") ---");
        Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().contains("Trajectory") || t.getName().equals("main"))
            .sorted((a, b) -> a.getName().compareTo(b.getName()))
            .forEach(t -> System.out.printf("  %-40s daemon=%-5s state=%s%n",
                t.getName(), t.isDaemon(), t.getState()));
        System.out.println("  (只显示 main 和 Trajectory 相关，过滤掉了 GC/编译器等 JVM 线程)");
    }

    /** 用 JMX 查看指定线程的详细状态（等价于 jstack 里看到的信息） */
    private static void printThreadState(String name, String title) {
        System.out.println("\n--- 线程状态: " + name + " (" + title + ") ---");
        ThreadMXBean mx = ManagementFactory.getThreadMXBean();
        long[] ids = mx.getAllThreadIds();
        boolean found = false;
        for (long id : ids) {
            ThreadInfo info = mx.getThreadInfo(id);
            if (info != null && info.getThreadName().equals(name)) {
                found = true;
                System.out.println("  状态: " + info.getThreadState());
                System.out.println("  卡在哪: " + info.getLockName());
                StackTraceElement[] stack = info.getStackTrace();
                if (stack.length > 0) {
                    System.out.println("  栈顶方法: " + stack[0]);
                }
            }
        }
        if (!found) {
            System.out.println("  线程已不存在（已退出）");
        }
    }
}
