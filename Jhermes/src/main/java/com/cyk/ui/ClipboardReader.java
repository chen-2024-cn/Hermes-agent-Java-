package com.cyk.ui;

import com.cyk.util.ProcessTerminator;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Explicit clipboard access only. No shell interpolation, polling, or unbounded process waits. */
@FunctionalInterface
public interface ClipboardReader {
    record Content(String text, ImageAttachment image) { }
    Content read() throws IOException;

    static ClipboardReader system() { return new WindowsClipboard(); }

    final class WindowsClipboard implements ClipboardReader {
        private static final int MAX_OUTPUT_BYTES = 20 * 1024 * 1024;
        private static final String SCRIPT = """
                $ErrorActionPreference = 'Stop'
                $ProgressPreference = 'SilentlyContinue'
                [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
                Add-Type -AssemblyName System.Windows.Forms
                Add-Type -AssemblyName System.Drawing
                $text = ''
                if ([System.Windows.Forms.Clipboard]::ContainsText()) {
                    $text = [System.Windows.Forms.Clipboard]::GetText()
                    if ($text.Length -gt 1000000) { throw 'Clipboard text exceeds limit' }
                }
                $encoded = ''
                if ([System.Windows.Forms.Clipboard]::ContainsImage()) {
                    $image = [System.Windows.Forms.Clipboard]::GetImage()
                    try {
                        if (([long]$image.Width * $image.Height) -gt 25000000) { throw 'Image pixels exceed limit' }
                        $memory = [System.IO.MemoryStream]::new()
                        try {
                            $image.Save($memory, [System.Drawing.Imaging.ImageFormat]::Png)
                            if ($memory.Length -gt 10485760) { throw 'Image exceeds 10MB limit' }
                            $encoded = [Convert]::ToBase64String($memory.ToArray())
                        } finally { $memory.Dispose() }
                    } finally { if ($image) { $image.Dispose() } }
                }
                @{text=$text; image=$encoded} | ConvertTo-Json -Compress
                """;

        @Override public Content read() throws IOException {
            if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
                throw new IOException("当前剪贴板入口支持 Windows; 其它环境可用 /image 附加图片");
            }
            String script = Base64.getEncoder().encodeToString(SCRIPT.getBytes(StandardCharsets.UTF_16LE));
            Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-Sta", "-EncodedCommand", script).redirectErrorStream(true).start();
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var output = executor.submit(() -> {
                    byte[] bytes = process.getInputStream().readNBytes(MAX_OUTPUT_BYTES + 1);
                    if (bytes.length > MAX_OUTPUT_BYTES) {
                        ProcessTerminator.destroyTree(process);
                        throw new IOException("剪贴板结果超过限制");
                    }
                    return bytes;
                });
                try {
                    if (!process.waitFor(15, TimeUnit.SECONDS)) throw new IOException("读取剪贴板超时");
                    byte[] bytes = output.get(2, TimeUnit.SECONDS);
                    if (process.exitValue() != 0) {
                        // PowerShell errors may include clipboard contents; do not echo them.
                        throw new IOException("无法读取剪贴板, 请检查图片大小或稍后重试");
                    }
                    com.fasterxml.jackson.databind.JsonNode root;
                    try {
                        root = new ObjectMapper().readTree(new String(bytes, StandardCharsets.UTF_8).strip());
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        throw new IOException("剪贴板返回数据无法解析");
                    }
                    String text = root.path("text").asText("");
                    String encoded = root.path("image").asText("");
                    ImageAttachment image = encoded.isEmpty() ? null
                            : ImageAttachment.fromBytes("剪贴板图片", Base64.getDecoder().decode(encoded));
                    return new Content(text, image);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("读取剪贴板已取消", e);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    throw new IOException("读取剪贴板失败", e);
                } finally {
                    if (process.isAlive()) ProcessTerminator.destroyTree(process);
                    output.cancel(true);
                }
            }
        }
    }
}
