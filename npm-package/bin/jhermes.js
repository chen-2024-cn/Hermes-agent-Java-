#!/usr/bin/env node
'use strict';
/**
 * Jhermes CLI 入口（npm bin shim）。
 *
 * 职责链：定位 hermes.jar（找不到则懒加载下载兜底）→ 定位 java → spawn 子进程
 *
 * 关键点：
 *   - jar 懒下载：不依赖 postinstall（npm 新版本可能拦截安装脚本），首次运行发现无 jar
 *     时现场下载（复用 scripts/download-jar.js 的重试逻辑），成功后才 spawn Java
 *   - 命令名 Jhermes 由 package.json 的 bin 字段注册（npm 据此生成 shim）
 *   - stdio:'inherit' 让 Java 进程直接接管终端（交互式对话、Ctrl+C 都正常）
 *   - 透传退出码，保证脚本化调用（if errorlevel 1）语义不被破坏
 */
const { spawn, spawnSync } = require('child_process');
const fs = require('fs');
const os = require('os');
const path = require('path');

const PKG_ROOT = path.join(__dirname, '..');

/**
 * 按优先级定位 jar：
 * 1. SKHERMES_JAR 环境变量（显式指定，开发/多版本共存用）
 * 2. 包内 jar/hermes.jar（捆绑发布 或 postinstall 从 Release 下载的）
 * 3. ~/.hermes/hermes.jar（兼容 install-hermes.ps1 的老安装位置）
 */
function resolveJar() {
  const candidates = [
    process.env.SKHERMES_JAR,
    path.join(PKG_ROOT, 'jar', 'hermes.jar'),
    path.join(os.homedir(), '.hermes', 'hermes.jar'),
  ];
  for (const c of candidates) {
    // 加体积校验（>1MB）：排除之前下载中断遗留的空文件/半截 jar，避免把坏文件当好文件用
    if (c && fs.existsSync(c) && fs.statSync(c).size > 1024 * 1024) return c;
  }
  return null;
}

/** 定位 java：JAVA_HOME 优先（版本可控），否则交给 PATH。 */
function findJava() {
  if (process.env.JAVA_HOME) {
    const exe = process.platform === 'win32' ? 'java.exe' : 'java';
    const cand = path.join(process.env.JAVA_HOME, 'bin', exe);
    if (fs.existsSync(cand)) return cand;
  }
  return 'java';
}

async function main() {
  // 找 jar：找不到 → 懒加载下载兜底（不依赖 postinstall，这是可靠性的核心）
  let jar = resolveJar();
  if (!jar) {
    const { ensureJar, resolveJarUrl } = require('../scripts/download-jar');
    const dest = path.join(PKG_ROOT, 'jar', 'hermes.jar');
    const ok = await ensureJar({ dest, url: resolveJarUrl(PKG_ROOT), maxAttempts: 5 });
    if (!ok) {
      console.error('[sk-hermes] 无法获取 hermes.jar，退出。请按上方提示手动提供 jar 后重试。');
      process.exit(1);
    }
    jar = dest;
  }

  // Windows 控制台默认代码页是 GBK，Java 侧用 -Dstdout.encoding=UTF-8 写出 UTF-8 字节，
  // 必须把控制台代码页也切到 UTF-8，两边编码对齐才不乱码（与 Jhermes.cmd 的 chcp 65001 同理）。
  // chcp 是 chcp.com 这个真实可执行文件，spawnSync 不带 shell 时必须写全名。
  if (process.platform === 'win32') {
    try {
      spawnSync('chcp.com', ['65001'], { stdio: 'ignore' });
    } catch (_) {
      /* best-effort：失败也不阻断启动 */
    }
  }

  const child = spawn(
    findJava(),
    [
      '-Dfile.encoding=UTF-8',
      '-Dstdout.encoding=UTF-8',
      '-Dstderr.encoding=UTF-8',
      '-jar', jar,
      ...process.argv.slice(2),
    ],
    { stdio: 'inherit' }
  );

  // POSIX 下把信号转发给子进程（Windows 控制台 Ctrl+C 由系统直接发给整个进程组，无需转发）
  const forward = (sig) => { try { child.kill(sig); } catch (_) { /* 已退出 */ } };
  process.on('SIGINT', forward);
  process.on('SIGTERM', forward);

  child.on('error', (err) => {
    console.error(`[sk-hermes] 无法启动 java（${err.message}）。`);
    console.error('请确认已安装 JDK 21+ 且 java 在 PATH 中，或设置 JAVA_HOME 环境变量。');
    process.exit(1);
  });

  // 透传退出码：CLI 的退出码是与调用方的返回值契约，不能丢
  child.on('close', (code, signal) => {
    process.exit(signal ? 1 : (code === null ? 1 : code));
  });
}

main();
