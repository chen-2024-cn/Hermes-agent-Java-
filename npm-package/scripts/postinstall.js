'use strict';
/**
 * postinstall 钩子：安装后 best-effort 预下载 hermes.jar。
 *
 * 定位是「加分项」而非「必需项」——下载逻辑同时内置于 bin/hermes.js（首次运行懒加载兜底），
 * 因此即使本脚本被 npm 的 allow-scripts / ignore-scripts 策略拦截（npm 新版本默认可能拦），
 * 或下载因网络失败，用户敲 hermes 时依然会自动补下载，不影响可用性。
 *
 * 铁律：任何情况下都 exit 0，绝不阻断 npm install。
 * 真正的下载实现见 ./download-jar.js（bin 与此处共用同一份，避免逻辑漂移）。
 */
const path = require('path');
const { ensureJar, resolveJarUrl } = require('./download-jar');

process.on('uncaughtException', (err) => {
  process.stdout.write(`[sk-hermes] postinstall 异常（已忽略，不阻断安装）：${err && err.message}\n`);
  process.exit(0);
});

const PKG_ROOT = path.join(__dirname, '..');

(async () => {
  // 安装阶段少试几次（maxAttempts=3）；失败无所谓，首次运行 hermes 时还会带 5 次重试再下
  await ensureJar({
    dest: path.join(PKG_ROOT, 'jar', 'hermes.jar'),
    url: resolveJarUrl(PKG_ROOT),
    maxAttempts: 3,
  });
  process.exit(0);
})();

