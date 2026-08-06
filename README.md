# Hermes 智能体

> 一个可自我进化的 AI 智能体 — 具备工具调用、记忆系统、技能匹配和 RAG 知识库的终端助手。

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

---

## 目录

- [简介](#简介)
- [核心能力](#核心能力)
- [架构概览](#架构概览)
- [快速开始](#快速开始)
- [工具系统](#工具系统)
- [RAG 引擎](#rag-引擎)
- [记忆系统](#记忆系统)
- [技能系统](#技能系统)
- [配置说明](#配置说明)
- [开发指南](#开发指南)

---

## 简介

Hermes 是一个运行在终端中的 AI 智能体，基于 Java 21 构建。它不仅能与大语言模型对话，还能**调用工具**完成实际任务（读写文件、搜索网页、管理记忆），并具备**RAG 知识库**能力，可对本地文档建立索引并进行语义检索。

最核心的设计理念是 **"可自我进化"**：Hermes 会记录每次对话的"轨迹"，自动从中提取知识存入记忆，让下一次对话比上一次更"懂你"。

```
┌──────────────────────────────────────────────────┐
│                    Hermes Agent                    │
│                                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐ │
│  │ 模型对话 │  │ 工具系统 │  │   RAG 引擎       │ │
│  │ DeepSeek │  │ 7大工具  │  │ 文档→索引→检索   │ │
│  └──────────┘  └──────────┘  └──────────────────┘ │
│                                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐ │
│  │ 记忆系统 │  │ 技能匹配 │  │   轨迹收集器      │ │
│  │ 跨会话   │  │ 自动路由 │  │   知识提取器      │ │
│  └──────────┘  └──────────┘  └──────────────────┘ │
└──────────────────────────────────────────────────┘
```

---

## 核心能力

| 能力 | 说明 |
|------|------|
| 🤖 **多轮对话** | 与 DeepSeek 大模型进行多轮交互，支持 tool calling |
| 🔧 **工具调用** | 文件读写、网页搜索、网页抓取、记忆管理等 7 类工具 |
| 🧠 **持久化记忆** | 跨会话记忆系统，记住你的偏好和项目上下文 |
| 📚 **RAG 引擎** | 本地文档索引 → 向量检索 → RRF 混合融合 → 语义搜索 |
| 🎯 **技能匹配** | 根据用户意图自动匹配合适的系统技能 |
| 🔄 **自我进化** | 轨迹收集 + 知识提取，越用越聪明 |

---

## 架构概览

```
sk-hermes/
├── agent/          # Agent 核心循环（对话→工具调用→反思）
├── bean/           # 数据模型（消息、会话、工具定义）
├── command/        # CLI 命令（picocli）
├── config/         # YAML 配置加载
├── constant/       # 常量定义
├── controller/     # 轨迹收集器
├── extract/        # 知识提取器
├── http/           # 模型 HTTP 客户端
├── manager/        # 记忆管理器 / 会话管理器 / 技能管理器
├── rag/            # RAG 引擎
│   ├── chunking/   #   文档分块（固定大小 / Markdown 感知）
│   ├── embedding/  #   向量化（OpenAI 兼容接口）
│   ├── parser/     #   文档解析（TXT / Markdown / PDF）
│   ├── rerank/     #   重排序接口
│   ├── search/     #   混合搜索（向量 + 关键词 RRF 融合）
│   └── store/      #   向量存储（PostgreSQL pgvector）
└── tool/           # 工具实现
```

---

## 快速开始

### 环境要求

- **JDK 21** 或更高版本
- **Maven 3.8+**
- **PostgreSQL + pgvector 扩展**（仅 RAG 功能需要）

### 1. 克隆项目

```bash
git clone https://github.com/chen-2024-cn/Hermes-agent-Java-.git
cd Hermes-agent-Java-/sk-hermes
```

### 2. 配置 API Key

首次运行会自动生成默认配置，也可手动创建 `~/.hermes/config.yml`：

```yaml
model:
  model: deepseek-v4-pro
  base_url: https://api.deepseek.com
  api_key: your-deepseek-api-key

agent:
  max_turns: 30
  temperature: 0.7
  max_tokens: 4096
```

### 3. 编译运行

```bash
# 编译
mvn clean compile

# 运行对话
mvn exec:java -Dexec.mainClass="com.cyk.HermesAgent" -Dexec.args="chat"

# 或打包为 fat jar
mvn clean package
java -jar target/sk-hermes-1.0-SNAPSHOT.jar chat
```

---

## 工具系统

Hermes 内置 7 类工具，AI 会根据你的请求自动选择合适的工具：

| 工具 | 功能 | 示例 |
|------|------|------|
| 📄 **FileTool** | 文件读写、搜索、Grep | "帮我读一下 pom.xml" |
| 🌐 **WebSearchTool** | 网页搜索 | "搜索 Java 21 新特性" |
| ⬇️ **FetchPageTool** | 抓取网页内容 | "抓取这篇文章的内容" |
| 🧠 **MemoryTool** | 记忆的增删改查 | "记住我喜欢用 VS Code" |
| 🔧 **SkillTool** | 技能发现与调用 | "帮我生成一个流程图" |
| 📚 **RagTool** | 知识库索引与检索 | "索引 docs/ 目录下的文档" |

---

## RAG 引擎

完整的技术栈：**文档解析 → 分块 → 向量化 → PostgreSQL 存储 → 混合检索**

```
PDF/MD/TXT 文件
    │
    ▼
DocumentParser ──→ Chunking ──→ Embedding ──→ PgVectorStore
    │                  │              │              │
 TextParser        FixedSize     OpenAiEmbedding   PostgreSQL
 MarkdownParser    MarkdownAware   (兼容接口)       + pgvector
 PdfParser
                                                    │
    ┌───────────────────────────────────────────────┘
    ▼
HybridSearcher ──→ Reranker ──→ 最终结果
  (向量 0.7 + 关键词 0.3)
```

### 启用 RAG

在 `~/.hermes/config.yml` 中设置：

```yaml
rag:
  enabled: true
  pgvector:
    url: jdbc:postgresql://localhost:5432/hermes_rag
    username: hermes
    password: your-password
  embedding:
    model: BAAI/bge-m3
    base_url: https://api.siliconflow.cn
    api_key: your-api-key
    dimension: 1024
  chunking:
    size: 512
    overlap: 64
  search:
    vector_weight: 0.7
    keyword_weight: 0.3
    default_top_k: 5
```

---

## 记忆系统

记忆系统让 Hermes 在**跨会话**中保持上下文感知，分为三类：

| 记忆类型 | 存储路径 | 用途 |
|----------|----------|------|
| 项目记忆 | `.hermes/memories/MEMORY.md` | 项目上下文、架构决策 |
| 用户画像 | `.hermes/memories/USER.md` | 你的偏好、习惯、背景 |
| 轨迹记录 | `.hermes/trajectories/` | 对话轨迹，用于知识提取 |

记忆文件使用 Markdown 格式，你随时可以手动编辑。

---

## 技能系统

技能是 Hermes 能力的扩展单元。当用户请求某个任务时，技能匹配器会自动识别意图并路由到对应技能。

```
用户: "帮我画一个系统架构图"
  → SkillMatcher 匹配 [processon-diagram-generator]
  → 调用技能生成可编辑在线图表
```

---

## 配置说明

完整配置项参考：

```yaml
model:
  model: deepseek-v4-pro        # 模型名称
  base_url: https://api.deepseek.com
  api_key: ""                    # API 密钥

agent:
  max_turns: 30                 # 最大对话轮次
  temperature: 0.7              # 生成温度
  max_tokens: 4096              # 最大输出 token
  gateway_timeout: 300          # 请求超时（秒）

tools:
  enabled:                      # 启用的工具列表
    - terminal
    - file_operations

rag:
  enabled: false                # RAG 开关
  # ... 见上文 RAG 配置
```

---

## 开发指南

### 运行测试

```bash
# 运行单元测试（排除集成测试）
mvn test

# 运行集成测试（需要 Docker）
mvn test -Dgroups=integration
```

### 项目依赖

| 组件 | 用途 |
|------|------|
| picocli | CLI 命令行框架 |
| OkHttp | HTTP 客户端 |
| Jackson | JSON / YAML 序列化 |
| Jsoup | HTML 解析 |
| Playwright | 无头浏览器（SPA 页面回退） |
| PDFBox | PDF 文档解析 |
| PostgreSQL JDBC | 向量数据库连接 |
| Testcontainers | 集成测试容器 |
| JUnit 5 + AssertJ | 测试框架 |

### 添加新工具

1. 在 `tool/` 包下创建工具类
2. 实现工具的 `call()` 方法
3. 在 Agent 初始化时调用 `toolRegistry.register()`

---

## License

MIT
