package com.cyk.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * <h1>原子文件写入（Atomic File Writer）</h1>
 *
 * <p>解决「进程在写文件途中被强杀/断电，导致文件损坏为半截或空」的经典崩溃一致性
 * （crash consistency）问题。适用于 MEMORY.md、session json 这类「宁可保留旧版本，
 * 也绝不能出现半成品」的关键持久化数据。</p>
 *
 * <h2>为什么 {@code Files.writeString} 不够安全</h2>
 * <p>{@code Files.writeString(path, content)} 内部是 {@code open → truncate → write → close}
 * 四步非原子操作。若在「truncate 之后、write 完成之前」的窗口被 {@code TerminateProcess}
 * 或断电，磁盘上会留下一个<b>空文件或截断的半截内容</b>，原有完整数据被破坏。</p>
 *
 * <h2>本实现的原子性保证（write-temp-then-rename 标准范式）</h2>
 * <ol>
 *   <li><b>同目录临时文件</b>：临时文件建在目标文件<b>同一目录</b>下——rename 的原子性
 *       仅在同一文件系统内成立，跨盘 rename 会退化为「复制+删除」非原子。</li>
 *   <li><b>fsync 数据落盘</b>：写完临时文件后 {@code force(true)} 把数据真正刷到磁盘，
 *       防止「rename 已生效但数据还在 OS 页缓存」时断电造成空文件。</li>
 *   <li><b>ATOMIC_MOVE 替换</b>：{@code Files.move(..., ATOMIC_MOVE)} 在 POSIX 上映射到
 *       {@code rename(2)}（原子系统调用）；在 Windows 上映射到 {@code MoveFileEx} 的
 *       {@code REPLACE_EXISTING}。任一时刻观察者看到的要么是旧完整文件、要么是新完整文件。</li>
 *   <li><b>失败清理</b>：任何一步失败都在 finally 里删除临时文件，不留垃圾。</li>
 * </ol>
 *
 * <h2>降级策略</h2>
 * <p>极少数文件系统/跨设备场景不支持 ATOMIC_MOVE（抛 {@link AtomicMoveNotSupportedException}），
 * 此时<b>降级为普通 REPLACE_EXISTING</b>——虽失去严格原子性，但仍保留「先在临时文件写完整、
 * 再替换」的好处（不会 truncate 目标文件后写一半），远优于直接 writeString。</p>
 *
 * <p>该工具为纯静态方法、无状态，线程安全由调用方（各自的写锁）保证。</p>
 */
public final class AtomicFileWriter {

    private static final Logger logger = LoggerFactory.getLogger(AtomicFileWriter.class);

    /** 临时文件前缀，便于识别与清理遗留 */
    private static final String TMP_PREFIX = ".hermes-atomic-";

    private AtomicFileWriter() {
        // 工具类禁止实例化
    }

    /**
     * 以原子方式把字符串写入目标文件（覆盖写）。
     *
     * @param target  目标文件路径
     * @param content 要写入的完整内容
     * @throws IOException 写入或替换失败（临时文件已被清理）
     */
    public static void writeStringAtomically(Path target, String content) throws IOException {
        writeBytesAtomically(target, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 以原子方式把字节写入目标文件（覆盖写）。
     *
     * @param target 目标文件路径
     * @param bytes  要写入的完整字节
     * @throws IOException 写入或替换失败（临时文件已被清理）
     */
    public static void writeBytesAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        // 目标在根目录等极端情况下 parent 可能为 null，回退到当前目录
        Path dir = (parent != null) ? parent : Path.of(".");
        Files.createDirectories(dir);

        // 临时文件名含随机串：避免同目录并发写同一目标时临时文件互相踩踏
        Path tmp = dir.resolve(TMP_PREFIX + UUID.randomUUID() + ".tmp");

        try {
            // ①+② 单个 FileChannel 完成「写数据 + fsync 强制落盘」，避免两次打开句柄。
            //    CREATE+WRITE+TRUNCATE_EXISTING：临时文件若已存在（理论不会）也保证是干净覆盖。
            try (FileChannel ch = FileChannel.open(tmp,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                // FileChannel.write 可能只写入部分字节（尤其大内容），必须循环直到写完
                while (buffer.hasRemaining()) {
                    ch.write(buffer);
                }
                // force(true)：把数据 + 文件元数据（长度等）真正刷到物理磁盘。
                // 少了这一步，断电时可能出现「目标已被 rename 指向临时文件，但其内容还在 OS 页缓存未落盘」= 空/半截文件。
                ch.force(true);
            }

            // ③ 原子替换目标文件
            moveAtomically(tmp, target);

            // ④ fsync 目录项：确保「新文件名的目录项」本身也落盘，
            //    否则断电后目录可能仍指向已被删除的旧 inode。
            fsyncDirectory(dir);

        } catch (IOException | RuntimeException e) {
            // 任何失败都清理临时文件，绝不在磁盘留垃圾
            deleteQuietly(tmp);
            throw e;
        }
        // 成功路径下 tmp 已被 move 走，无需清理
    }

    /**
     * 原子 move：优先 ATOMIC_MOVE，不支持时降级 REPLACE_EXISTING（保留临时文件先写完整的优势）。
     */
    private static void moveAtomically(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            logger.warn("文件系统不支持原子移动，降级为普通替换：{} -> {} ({})", tmp, target, e.getMessage());
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * fsync 目录本身（POSIX 语义：目录也是文件，其目录项变更需要单独 force）。
     * Windows 上对目录 open FileChannel 通常不支持，静默跳过即可——
     * Windows 的 MoveFileEx + NTFS 日志已提供足够的崩溃一致性。
     */
    private static void fsyncDirectory(Path dir) {
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException e) {
            // 目录 fsync 失败不影响数据文件的原子替换结果，仅记 DEBUG（Windows 上属正常现象）
            logger.debug("目录 fsync 跳过（当前文件系统可能不支持）：{} ({})", dir, e.getMessage());
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            logger.debug("清理临时文件失败（忽略）：{} ({})", path, e.getMessage());
        }
    }
}
