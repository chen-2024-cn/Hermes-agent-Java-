# sk-hermes-cli

`sk-Hermes`（Java AI Agent）的 npm 分发壳。装完即可在任意目录敲 `hermes` 或 `sk-hermes` 进入对话，
等价于 Claude Code 的 `claude` 命令。

> 本包只是一个 **JS → Java 的桥接 shim**：`bin/hermes.js` 用 `child_process.spawn` 启动 `java -jar hermes.jar`，
> 并透传参数、stdin/stdout、退出码。npm 在这里只承担「分发 + 注册全局命令」的职责；
> 181MB 的 jar 不进 npm 包，而是发布到 **GitHub Release**，安装时自动下载（esbuild 同款思路）。

## 用户体验（安装者视角）

```bash
npm install -g sk-hermes-cli
# postinstall 自动从 GitHub Release 下载 hermes.jar
hermes          # 任意目录直接对话，模型以当前目录为项目根
```

### 安装者前置依赖
- **JDK 21+**（`java` 在 PATH 中，或设置 `JAVA_HOME`）
- 首次运行前配置 `~/.skhermes/config.yaml` 的 `model.api_key`
- 能访问 GitHub（下载 jar）；被墙时可设 `SKHERMES_JAR_URL` 指向镜像，或 `SKHERMES_JAR` 指向本地文件

### 使用

```bash
hermes              # 在当前目录直接进入对话，模型以当前目录为项目根
hermes -m fast      # 指定模型别名
hermes --help       # 查看全部子命令（chat / sessions / resume）
hermes chat -s      # 显式子命令 + 流式输出
```

### jar 查找优先级（运行时）
1. 环境变量 `SKHERMES_JAR`（显式指定，适合多版本共存 / 开发调试）
2. 包内 `jar/hermes.jar`（postinstall 下载的落地位置，或作者手动捆绑）
3. `~/.hermes/hermes.jar`（兼容 `tools/install-hermes.ps1` 的本地安装）

---

## 作者发布教程（模式 B：GitHub Release 按需下载）

> 下载地址不再依赖环境变量：**package.json 内置了默认 `hermes.jarUrl`**（本仓库发布版已指向
> `https://github.com/chen-2024-cn/Hermes-agent-Java-/releases/download/v1.0.0/hermes.jar`），
> 环境变量 `SKHERMES_JAR_URL` 仅作临时覆盖（优先于内置值）。

### 第 0 步：一次性准备（只做一次）

| 检查项 | 命令 | 要求 |
|---|---|---|
| GitHub CLI | `gh auth status` | 已登录且 token 有 repo 权限 |
| npm 账号 | `npm whoami` | 未登录则 `npm login`（浏览器注册/验证） |
| 仓库可见性 | GitHub 仓库设置 | 必须 **public**（Release 资产匿名可下载，postinstall 才不用带 token） |
| 包名可用性 | `npm view sk-hermes-cli` | 返回 404 = 未占用，可直接用 |

### 第 1 步：构建 jar 并发布到 GitHub Release（一键脚本，幂等）

```powershell
cd <项目根>
powershell -ExecutionPolicy Bypass -File tools\release.ps1
# 指定版本号: -Version 1.1.0（默认取 npm-package\package.json 的 version）
```

脚本内部等价于三步（理解原理用）：

```powershell
mvn -f sk-hermes\pom.xml clean package -DskipTests        # ① 构建 fat jar
gh release create v1.0.0 --title "..." --target master    # ② 建 Release（tag 须与 jarUrl 一致）
Copy-Item sk-hermes\target\sk-hermes-1.0-SNAPSHOT.jar $env:TEMP\hermes.jar   # ③ 先复制成 hermes.jar
gh release upload v1.0.0 $env:TEMP\hermes.jar --clobber   #    再上传（见下方"坑"）
```

> ⚠️ **gh 的 `path#name` 重命名语法不可靠**（gh 2.97 实测 `#name` 只是 Web UI 显示标签，
> 资产真实名始终 = 源文件 basename）。而 postinstall 下载 URL 尾段必须是 `hermes.jar`，
> 所以**必须先把 jar 复制成物理文件名 `hermes.jar` 再上传**，否则用户安装时 404。
> release.ps1 已内置这个复制步骤 + 上传后资产核验（大文件上传可能静默失败）。

**验证下载地址可用**（期望 HTTP/2 200）：

```powershell
curl.exe -sIL "https://github.com/chen-2024-cn/Hermes-agent-Java-/releases/download/v1.0.0/hermes.jar" | Select-Object -First 1
```

### 第 2 步：核对 package.json 内置地址

```json
"hermes": { "jarUrl": "https://github.com/<owner>/<repo>/releases/download/v1.0.0/hermes.jar" }
```

发新版本时 tag 变了，两种策略二选一：
- **URL 带具体 tag**（当前方案）：每次发版改一行；地址永久有效、支持版本回滚
- **`/releases/latest/download/hermes.jar`**：永远指向最新 Release，发版不用改；但用户无法锁旧版

### 第 3 步：发布前本地全链路验证（隔离，不污染全局）

```powershell
cd npm-package
npm pack --dry-run          # 包内应只有 3~4 个小文件（无 jar），体积 ~3.5KB

$pfx = "$env:TEMP\hermes_test"; New-Item -ItemType Directory -Force -Path $pfx | Out-Null
npm install -g .\npm-package --prefix $pfx
#   期望输出: [sk-hermes] 正在从 https://github.com/... 下载 hermes.jar ...
#             [sk-hermes] jar 已下载到 ...\jar\hermes.jar（181.x MB）
& "$pfx\hermes.cmd" --version   # 期望 1.0.0，退出码 0
Remove-Item -Recurse -Force $pfx
```

> ⚠️ 若 npm 开启了 install-scripts 安全策略（提示 `allow-scripts`），postinstall 会被拦截，
> 执行 `npm approve-scripts sk-hermes-cli` 放行后重装。

### 第 4 步：发布

```powershell
cd npm-package
npm publish --access public
```

### 第 5 步：日常迭代发版循环

```powershell
mvn -f sk-hermes\pom.xml clean package -DskipTests          # 1) 构建
cd npm-package; npm version 1.1.0 --no-git-tag-version; cd ..   # 2) 升版本号（npm 禁止同版本重复发布）
powershell -ExecutionPolicy Bypass -File tools\release.ps1    # 3) 传新 tag 的 Release
#        4) 同步修改 package.json 的 hermes.jarUrl → v1.1.0
npm pack --dry-run; 第 3 步验证; npm publish                   # 5) 验证后发布
```

## 常见坑（全部实测踩过）

| 坑 | 现象 | 解法 |
|---|---|---|
| Release 资产名不对 | postinstall 下载 404 | 资产真名必须叫 `hermes.jar`；gh 的 `#重命名`不生效，须先 `Copy-Item` 成 hermes.jar 再 upload |
| 大文件上传静默失败 | 资产列表为空/不全 | `gh release view v1.0.0 --json assets` 核验；失败重跑 release.ps1（幂等，含上传后自动核验） |
| 仓库是 private | 匿名下载 404 | 仓库设 public；或 postinstall 带 token（复杂度高，不推荐） |
| npm 拦截 postinstall | 装完没 jar，运行时提示找不到 | `npm approve-scripts sk-hermes-cli` 或 `npm rebuild` |
| 包名被占用 | `npm publish` 403 | `npm view <名字>` 先探测；占用则换名或用 scoped 包 `@你/sk-hermes-cli` |
| 含中文 .ps1 报语法错 | "字符串缺少终止符"、输出乱码 | 必须存**带 BOM 的 UTF-8**（PS 5.1 无 BOM 按 GBK 解析） |
| gh stderr 触发 PS 终止 | `$ErrorActionPreference='Stop'` 时探测命令被误杀 | 探测类 gh 调用临时切回 Continue（见 release.ps1 的 Invoke-GhProbe） |

## 项目结构

```
npm-package/
├── package.json           # bin 声明（hermes + sk-hermes）+ hermes.jarUrl 内置下载地址
├── bin/hermes.js          # shim：定位 jar → 定位 java → spawn 透传（stdio inherit + 退出码）
├── scripts/postinstall.js # 安装钩子：env > jarUrl 两级取值下载；任何失败都 exit 0 不阻断安装
└── README.md
```

