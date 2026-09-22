'use strict';
/**
 * postinstall 钩子：安装后自动准备 hermes.jar。
 *
 * 设计成「优雅可跳过」，保证 npm install 永不因缺 jar 而报错中断：
 *   - 若包内已捆绑 jar（jar/hermes.jar 随包发布）→ 什么都不做；
 *   - 若设置了环境变量 SKHERMES_JAR_URL（指向 jar 下载地址，如 GitHub Release）→ 下载并落地；
 *   - 否则打印一句引导信息后正常退出（exit 0），不阻断安装。
 *
 * 这套「包不含大文件、装完按需下载」的思路与 esbuild / chromium 等重量级 npm 包一致，
 * 好处是 npm 包体积只有几 KB，拉取快、缓存友好；jar 由 CI 单独发到 Release。
 */
const fs = require('fs');
const path = require('path');
const https = require('https');

const PKG_ROOT = path.join(__dirname, '..');
const JAR_DIR = path.join(PKG_ROOT, 'jar');
const JAR_PATH = path.join(JAR_DIR, 'hermes.jar');

// 下载地址两级优先：环境变量 SKHERMES_JAR_URL（用户/CI 临时覆盖） > package.json 内置 hermes.jarUrl（发布时写死，普通用户零配置）
const pkgJson = (() => {
  try {
    return JSON.parse(fs.readFileSync(path.join(PKG_ROOT, 'package.json'), 'utf8'));
  } catch (_) {
    return {};
  }
})();
const URL = process.env.SKHERMES_JAR_URL || (pkgJson.hermes && pkgJson.hermes.jarUrl) || '';

function log(msg) {
  process.stdout.write(`[sk-hermes] ${msg}\n`);
}

// 1. 已捆绑 → 直接跳过
if (fs.existsSync(JAR_PATH) && fs.statSync(JAR_PATH).size > 1024 * 1024) {
  log(`检测到已捆绑 jar：${JAR_PATH}，跳过下载。`);
  process.exit(0);
}

// 2. 包内没写下载地址、环境变量也没给 → 引导用户，正常退出
if (!URL) {
  log('未找到 jar 下载地址（package.json 的 hermes.jarUrl 与环境变量 SKHERMES_JAR_URL 均未设置），跳过自动下载。');
  log('后续可通过以下任一方式提供 jar：');
  log('  a) 设环境变量 SKHERMES_JAR 指向你的 sk-hermes-*.jar');
  log('  b) 将构建产物复制为 ' + JAR_PATH);
  log('  c) 设 SKHERMES_JAR_URL=<下载地址> 后重新 npm install');
  process.exit(0);
}

// 3. 下载 jar（带 302/301 重定向跟随，GitHub Release 的下载地址会重定向到 CDN）
function download(url, dest, depth = 0) {
  if (depth > 5) {
    log('重定向次数过多，下载失败。');
    return process.exit(0); // 仍不阻断安装
  }
  const req = https.get(url, { headers: { 'User-Agent': 'sk-hermes-npm' } }, (res) => {
    const code = res.statusCode || 0;
    if (code >= 300 && code < 400 && res.headers.location) {
      res.resume();
      const next = new URL(res.headers.location, url).toString();
      return download(next, dest, depth + 1);
    }
    if (code !== 200) {
      log(`下载失败：HTTP ${code}（${url}）`);
      res.resume();
      return process.exit(0);
    }
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    const file = fs.createWriteStream(dest);
    res.pipe(file);
    file.on('finish', () => {
      file.close();
      const mb = (fs.statSync(dest).size / 1024 / 1024).toFixed(1);
      log(`jar 已下载到 ${dest}（${mb} MB）。`);
      process.exit(0);
    });
  });
  req.on('error', (err) => {
    log(`下载异常：${err.message}`);
    process.exit(0); // 优雅降级，不阻断 npm install
  });
}

log(`正在从 ${URL} 下载 hermes.jar ...`);
download(URL, JAR_PATH);
