#!/usr/bin/env node
'use strict';
/**
 * hermes CLI 入口（npm bin shim）。
 *
 * 职责链：定位 hermes.jar → 定位 java → spawn 子进程
 * 关键点：stdio:'inherit' 让 Java 进程直接接管终端（交互式对话、Ctrl+C 都正常），
 *        并透传退出码，保证脚本化调用（if errorlevel 1）语义不被破坏。
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
    if (c && fs.existsSync(c)) return c;
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

function main() {
  const jar = resolveJar();
  if (!jar) {
    console.error('[sk-hermes] 未找到 hermes.jar。三选一解决：');
    console.error('  1. 设置环境变量 SKHERMES_JAR 指向 jar 文件');
    console.error(`  2. 将构建产物复制到 ${path.join(PKG_ROOT, 'jar', 'hermes.jar')}`);
    console.error('  3. 设置 SKHERMES_JAR_URL 后运行: node ' + path.join(PKG_ROOT, 'scripts', 'postinstall.js'));
    process.exit(1);
  }

  // Windows 控制台默认代码页是 GBK，Java 侧用 -Dstdout.encoding=UTF-8 写出 UTF-8 字节，
  // 必须把控制台代码页也切到 UTF-8，两边编码对齐才不乱码（与 hermes.cmd 的 chcp 65001 同理）。
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
