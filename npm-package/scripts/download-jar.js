'use strict';
/**
 * 可复用的 hermes.jar 下载器。
 *
 * 被两处调用：
 *   - scripts/postinstall.js：安装时 best-effort 预下载（可能被 npm allow-scripts 策略拦截）
 *   - bin/hermes.js：首次运行时懒加载兜底（不依赖任何安装脚本，最可靠）
 *
 * 设计要点（针对不稳定网络，尤其国内访问 GitHub Release CDN 易被 Connection reset）：
 *   - 整包重试 + 递增退避（重试窗口内大概率能撞上一次通畅）
 *   - 重定向跟随（GitHub download 链接 302 到 CDN），手动解析 Location 不依赖 new URL
 *   - .part 临时文件 + 原子 rename，避免半截文件被后续启动误判为有效 jar
 *   - 单请求超时保护，防止连接假死挂起
 */
const fs = require('fs');
const path = require('path');
const https = require('https');
const http = require('http');

const MAX_REDIRECTS = 5;
const REQ_TIMEOUT_MS = 60000;

/** 解析下载地址：环境变量 SKHERMES_JAR_URL 优先，其次 package.json 内置 hermes.jarUrl。 */
function resolveJarUrl(pkgRoot) {
  let pkgJson = {};
  try {
    pkgJson = JSON.parse(fs.readFileSync(path.join(pkgRoot, 'package.json'), 'utf8'));
  } catch (_) {
    /* package.json 读失败则仅依赖环境变量 */
  }
  return process.env.SKHERMES_JAR_URL || (pkgJson.hermes && pkgJson.hermes.jarUrl) || '';
}

/**
 * 解析重定向 Location：支持绝对 URL 与相对路径，不依赖 new URL（避免环境差异与变量遮蔽）。
 */
function resolveRedirect(base, location) {
  if (/^https?:\/\//i.test(location)) return location;
  const m = base.match(/^(https?:)\/\/([^/]+)(\/.*)?$/i);
  if (!m) return location;
  if (location.startsWith('/')) return `${m[1]}//${m[2]}${location}`;
  const dir = (m[3] || '/').replace(/[^/]*$/, '');
  return `${m[1]}//${m[2]}${dir}${location}`;
}

/** 单次下载尝试（含重定向跟随）。resolve()=成功；reject(err)=可重试错误。 */
function downloadOnce(url, dest, redirects) {
  return new Promise((resolve, reject) => {
    const mod = url.startsWith('https') ? https : http;
    const req = mod.get(url, { headers: { 'User-Agent': 'sk-hermes-npm' } }, (res) => {
      const code = res.statusCode || 0;
      if (code >= 300 && code < 400 && res.headers.location) {
        res.resume();
        if (redirects >= MAX_REDIRECTS) return reject(new Error('重定向次数过多'));
        return downloadOnce(resolveRedirect(url, res.headers.location), dest, redirects + 1)
          .then(resolve, reject);
      }
      if (code !== 200) {
        res.resume();
        return reject(new Error(`HTTP ${code}（${url}）`));
      }
      fs.mkdirSync(path.dirname(dest), { recursive: true });
      const tmp = dest + '.part';
      const file = fs.createWriteStream(tmp);
      res.pipe(file);
      file.on('finish', () => {
        file.close(() => {
          try {
            fs.renameSync(tmp, dest); // 原子替换，避免半截文件
            resolve();
          } catch (e) {
            reject(e);
          }
        });
      });
      res.on('error', reject); // 服务端中途断流也走重试
    });
    req.setTimeout(REQ_TIMEOUT_MS, () => req.destroy(new Error(`请求超时（${REQ_TIMEOUT_MS}ms 无响应）`)));
    req.on('error', reject);
  });
}

/**
 * 确保 dest 处存在 jar：已存在（>1MB）则跳过；否则带重试下载。
 *
 * @param {object} opts
 * @param {string} opts.dest     目标 jar 路径
 * @param {string} opts.url      下载地址
 * @param {number} [opts.maxAttempts=5] 最大重试次数
 * @param {(msg:string)=>void} [opts.log] 日志输出（默认写 stdout）
 * @returns {Promise<boolean>} 是否最终拿到了 jar（true=可用；false=下载失败，调用方应给出人话提示）
 */
async function ensureJar({ dest, url, maxAttempts = 5, log = (m) => process.stdout.write(`[sk-hermes] ${m}\n`) }) {
  // 已有可用 jar（>1MB，排除空文件/半截文件）→ 直接用
  if (fs.existsSync(dest)) {
    try {
      if (fs.statSync(dest).size > 1024 * 1024) return true;
    } catch (_) {
      /* 忽略统计失败，继续走下载 */
    }
  }
  if (!url) {
    log('未找到 jar 下载地址（package.json 的 hermes.jarUrl 与环境变量 SKHERMES_JAR_URL 均未设置）。');
    log('请设 SKHERMES_JAR 指向你本地构建的 jar，或将 jar 放到 ' + dest);
    return false;
  }

  log(`正在下载 hermes.jar（约 45 MB，最多重试 ${maxAttempts} 次）...`);
  log(`来源: ${url}`);
  for (let attempt = 1; attempt <= maxAttempts; attempt++) {
    try {
      await downloadOnce(url, dest, 0);
      const mb = (fs.statSync(dest).size / 1024 / 1024).toFixed(1);
      log(`下载完成: ${dest}（${mb} MB）`);
      return true;
    } catch (err) {
      log(`第 ${attempt}/${maxAttempts} 次下载失败：${err.message}`);
      try { fs.unlinkSync(dest + '.part'); } catch (_) { /* 忽略 */ }
      if (attempt < maxAttempts) {
        const wait = attempt * 3000; // 3s→6s→9s→12s 递增退避
        log(`${wait / 1000}s 后重试...`);
        await new Promise((r) => setTimeout(r, wait));
      }
    }
  }
  log('自动下载失败（可能是网络无法稳定访问 GitHub CDN）。可手动解决：');
  log(`  用浏览器/下载工具获取 ${url}`);
  log(`  放到 ${dest}`);
  log(`  或设置环境变量 SKHERMES_JAR 指向该 jar 文件。`);
  return false;
}

module.exports = { ensureJar, resolveJarUrl, downloadOnce, resolveRedirect };
