package com.cyk.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.cyk.bean.ModelMessage;
import com.cyk.bean.TrajectoryEntry;
import com.cyk.bean.TrajectorySession;
import com.cyk.constant.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
    轨迹收集器 收集会话数据用来自我进化
 */
public class TrajectoryCollector {
    private static final Logger logger = LoggerFactory.getLogger(TrajectoryCollector.class);
    private static final ObjectMapper mapper = new ObjectMapper()
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS) //禁用将日期写为时间戳
        .registerModule(new JavaTimeModule()) // 注册 Java 8 时间模块
        .setSerializationInclusion(JsonInclude.Include.NON_NULL); // 序列化时忽略 null 值
    private final Path trajectoriesDir;
    private final Path completedFile;
    private final Path failedFile;
    private final Path compressedDir;

    // 队列
    /**
     * LinkedBlockingQueue 的核心特性
     * 线程安全：内部使用两把锁（putLock / takeLock），生产和消费操作可以并行，高并发下吞吐量优于 ArrayBlockingQueue（一把锁）
     * 阻塞语义：take() 是阻塞方法——当队列为空时，后台线程自动挂起等待，不消耗 CPU。一旦有数据入队，线程立即被唤醒处理。这就是 while (running) 循环不会空转的原因。
     * 默认无界：new LinkedBlockingQueue<>() 默认容量是 Integer.MAX_VALUE（约 21 亿），
     * 为什么选它?
     * 轨迹数据写入磁盘（Files.writeString）是 I/O 操作，速度远慢于内存操作。
     * 用这个队列做缓冲，业务线程只需把数据往队列一扔就返回，不影响主业务流程的响应速度。后台线程再慢慢消费、持久化到 JSONL 文件。这是典型的削峰填谷设计。
     */
    private final BlockingQueue<TrajectoryEntry> pendingTrajectories = new LinkedBlockingQueue<>();

    // 线程运行状态
    /**
     * volatile 的作用：保证可见性
     * 如果没有 volatile，Java 内存模型（JMM）允许每个线程将变量缓存在 CPU 寄存器或 L1/L2 缓存中。也就是说：
     * 当 shutdown 线程设置 running = false 时，这个写入可能只留在该线程的 CPU 缓存里
     * 后台线程在自己的循环中读取 running 时，可能永远读取到自己缓存中旧的 true，导致线程永远无法停止
     * volatile 强制了两件事：
     * 写操作立即刷新到主内存，对所有线程可见
     * 读操作始终从主内存获取，不读缓存
     */
    private volatile boolean running = false;

    // 后台线程
    private Thread processingThread;

    // 会话跟踪
    private final Map<String, TrajectorySession> activeSessions = new HashMap<>();

    public TrajectoryCollector() {
        this.trajectoriesDir = Constants.getHermesHome().resolve("trajectories");
        this.completedFile = trajectoriesDir.resolve("trajectory_samples.jsonl");
        this.failedFile = trajectoriesDir.resolve("failed_trajectories.jsonl");
        this.compressedDir = trajectoriesDir.resolve("compressed");

        try {
            Files.createDirectories(trajectoriesDir);
            Files.createDirectories(compressedDir);
        } catch (IOException e) {
            logger.error("Failed to create trajectories directory: {}", e.getMessage());
        }

        startBackgroundProcessing();
    }

    /**
     * 创建线程处理数据
     */
    private void startBackgroundProcessing() {
        this.running = true;
        processingThread = new Thread(this::processLoop);
        processingThread.setDaemon(true);//开启守护线程
        processingThread.setName("TrajectoryCollector-ProcessingThread");
        processingThread.start();
    }

    /**
     * 从队列中获取数据
     */
    private void processLoop() {
        while (running) {
            //从队列获取数据
            try {
                TrajectoryEntry entry = pendingTrajectories.take();

                //将数据保存到硬盘文件中
                processTrajectory(entry);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    /**
     * 处理数据
     * @param entry
     */
    private void processTrajectory(TrajectoryEntry entry) {
        //先简单保存
        saveTrajectory(entry);
    }

    /**
     * 保存数据
     */
    public void saveTrajectory(TrajectoryEntry entry) {
        try {
            Path targetFile = entry.isCompleted() ? completedFile : failedFile;

            //将entry转成json格式
            String json = mapper.writeValueAsString(entry);
            json += "\n";
            //保存数据 jsonl
            Files.writeString(targetFile, json,StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * 开启新的轨迹对话
     */
    public void startSession(String sessionId, String model) {
        TrajectorySession trajectorySession = new TrajectorySession(sessionId, model);

        activeSessions.put(sessionId, trajectorySession);
    }

    /**
     * 添加消息
     */
    public void addMessage(String sessionId, ModelMessage message) {
        TrajectorySession trajectorySession = activeSessions.get(sessionId);
        if (trajectorySession == null) {
            logger.warn("addMessage called for unknown sessionId: {}", sessionId);
            return;
        }
        trajectorySession.addMessage(message);//放入聊天对象
    }

    /**
     * 结束轨迹会话
     */
    public void endSession(String sessionId, boolean completed) {
        //从map中移除要结束的会话
        TrajectorySession trajectorySession = activeSessions.remove(sessionId);
        TrajectoryEntry trajectoryEntry = trajectorySession.toEntry(completed);
        //将聊天信息放入队列
        pendingTrajectories.offer(trajectoryEntry);
    }

    /**
     * 关闭轨迹收集器
     */
    public void shutdown() {
        running = false;

        //中断
        processingThread.interrupt();

        TrajectoryEntry entry;
        //保存队列中的数据
        while ((entry = pendingTrajectories.poll()) != null){
            saveTrajectory(entry);
        }
    }

}
