# sk-hermes-cli

`sk-Hermes`（Java AI Agent）的命令行工具。装完即可在任意目录敲 `hermes`，像 Claude Code 一样对话。

> 这是一个 **JS → Java 桥接壳**：`bin/hermes.js` 启动 `java -jar hermes.jar`。
> jar 不打进 npm 包（包仅 ~20KB），而是首次使用时从 GitHub Release 自动下载。

---

## 安装

**本包只在 npmjs 官方源发布**，直接安装即可：

```bash
npm install -g sk-hermes-cli
```

### 前置依赖
- **JDK 21+**（`java` 在 PATH 中，或设置 `JAVA_HOME`）
- 首次运行前，在 `~/.skhermes/config.yaml` 填入 `model.api_key`

---

## 🇨🇳 国内网络加速（强烈建议）

jar 托管在 GitHub Release，**直连实测仅 ~95 KB/s**（45 MB 要 8 分钟，还常断流）；走镜像可达 **~1.5 MB/s**。

**安装前**设一次环境变量即可（postinstall 与首次运行都会读它）：

```powershell
# PowerShell
$env:SKHERMES_JAR_URL = "https://gh-proxy.com/https://github.com/chen-2024-cn/Hermes-agent-Java-/releases/download/v1.0.0/hermes.jar"
npm install -g sk-hermes-cli
```

```bat
:: CMD
set SKHERMES_JAR_URL=https://gh-proxy.com/https://github.com/chen-2024-cn/Hermes-agent-Java-/releases/download/v1.0.0/hermes.jar
npm install -g sk-hermes-cli
```

```bash
# bash / zsh
export SKHERMES_JAR_URL="https://gh-proxy.com/https://github.com/chen-2024-cn/Hermes-agent-Java-/releases/download/v1.0.0/hermes.jar"
npm i -g sk-hermes-cli
```

> 装完 jar 没下下来？不必重装——直接敲 `hermes` 会自动补下载（带 5 次重试）。

---

## 使用

```bash
hermes              # 在当前目录直接进入对话，模型以当前目录为项目根
hermes -m fast      # 指定模型别名
hermes -s           # 开启流式输出
hermes --help       # 查看全部子命令（chat / sessions / resume）
hermes chat         # 显式使用 chat 子命令
```

首次运行会自动下载 hermes.jar（约 45 MB），之后直接启动。


