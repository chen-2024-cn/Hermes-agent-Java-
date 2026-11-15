# JH-SPEC-001 改造 · 交接清单（HANDOFF）

> 生成时间：2026-10-13。用途：新开会话/新 agent 接手本项目时的**单入口文档**。
> 阅读顺序建议：本文「六、新 agent 必读纪律」→「二、已完成」→「三、待办」→ 按需展开对应证据文档。
> 规范原文：`doc/Jhermes智能体提示词.md`（JH-SPEC-001 v2.0，**不入库**，勿删）。

## 一、项目快照

| 项 | 值 |
|---|---|
| 版本 | Jhermes 2.1.0（单一事实源 = `Jhermes/pom.xml`，构建期写入 `version.properties`） |
| 分支 | master，工作树 clean（最近提交见 git log） |
| 技术栈 | Java 21 + Maven（Wrapper `mvnw.cmd`，3.9.12）+ picocli + OkHttp + Jackson + JUnit5/AssertJ（无 Mockito） |
| 测试基线 | **799/799 全绿**（`mvnw clean verify` BUILD SUCCESS）；62 个测试类 |
| 运行环境 | Windows 11；pgvector 在 5433 端口；模型走内网路由 `ai-router.anker-in.com`（需完整模型组名，如 `anthropic/v_model/deepseek-v4-pro`，裸名 403） |
| LLM 请求体 | OpenAI 兼容 `/chat/completions`；已知缺陷：硬编码 `max_tokens`（见待办 1） |

## 二、已完成（P0 五项中的四项 + Phase 0）

| 阶段 | 结论 | 关键提交 | 证据/文档 |
|---|---|---|---|
| Phase 0 基线 | B1~B8 全部完成；token 基线 6,150（满配）、2.5 字符/token；C-08 证实（Map.of 跨进程字节漂移） | `fd3d4c3` `60100e7` `5936956` | `docs/baseline-report.md` |
| P0-1 工程基线 | 版本单一事实源、ci.yml 测试门禁（win+ubuntu）、Maven Wrapper | `4a82495` | `.github/workflows/ci.yml` |
| P0-2 命令门禁 | 四级分级（READ_ONLY/WRITE/DANGEROUS/BLOCKED）、确认闸门、审计日志（JSONL 含被拒）、敏感路径守卫、注入守卫；AC9 第一层 67/67 PASS | `ccdeba1` `428e162` `341ad82` | `docs/ac9-layer1-report.txt`、`docs/ac9-manual-verification.md`、`tools/ac9/` |
| P0-2 缺陷修复 | R10 取证发现：① PowerShell 只读 cmdlet 未入白名单（放大器：提示词教模型用 PowerShell）② Windows `/开关` 与 `%date%` 等被误判越界路径。修复后 AC9 重跑仍 67/67 | `e198515` | commit message（含审计日志取证原文） |
| P0-3 提示词工程化 | 六期全部完成：CapabilityRegistry 注册实况派生、11 段 md 外置 + manifest、PromptComposer、一致性自检（R6）、技能索引（R9）、目录快照注入过滤（R8）、SlashCommandRegistry + /help（AC3）、预算守卫入 CI、R10 抽样回归、AC4 实机 PASS | `b3bb83b`→`d514947`（6 期各独立提交） | `docs/p0-3-r10-sampling/REPORT.md`（R10）、`AC4-REPORT.md`（AC4 两层证据） |
| P0-5 运行稳定性 | LlmClient 抽象 + 指数退避、dispatch 异常边界、回合回滚 + /retry、逐轮持久化 + shutdown hook、resume system@0、文件/技能工具正确性 | `f4f15a9` `2750256` | — |

**当前提示词预算实测**（期 6-A 后）：满配首轮输入 ≈5,253 tokens（B2 基线 6,150 → 85.4%）；静态创作段 3,677 字符（基线 5,878 → 63%，T1 冻结线 80% 达标且余量充足）；30 技能压力 6,642 字符 ≤ 6,721 冻结线。

## 三、待办（按优先级）

### ⛔ P0-4 上下文预算与可度量性 —— 已出实现级报备，**阻塞在 4 个决策**

规范条款：R1~R10 与 AC1~AC6 见规范文档 L352-390。报备全文要点如下（新 agent 若开工，以此为准重新向用户确认）：

**D-1 T2 冻结口径（核心决策，必须用户拍板）**——实测算术：
- 冻结线 D-07：T2 ≤ 基线 55%（= 3,383 tokens = 8,447 字符，口径为「首轮整请求 / B2 基线」）
- 实测构成：system ≈6,167 字符（含 2,524 基线记忆）+ tools JSON 6,952（16 工具；description 合计仅 ≈1,060，**大头是 parameters ≈4,850 与骨架开销**；单工具最大 `skill_manage` 1,049 / `run_command` 714）
- 推演：即便 tools 压到 0，整请求仍剩 6,167 → **满配 16 工具下整请求 55% 数学不可达**（与 Phase 0 结论"联动约 63%"一致）
- 建议（报备原案）：双口径——tools JSON ≤ 自身基线 55%（硬门禁，紧瘦身可达 ≈3,825）+ 整请求首轮 ≤65%（观察线，改口径需用户批准）

**D-2** 元工具 `enable_toolset`（R6）：建议不预建（规范即"仅当 T2 未达才启用"）
**D-3** R10 评测规模：建议精简方案（"改动前"全量 15 场景跑 1 遍作基线，R5 后只跑受 schema 影响的 8 场景；≈11 万 tokens，全量方案 ≈20 万）
**D-4** 压缩参数（window 65536 / trigger 0.5 / keep 3 回合组）+ 新建 `Jhermes debug tools-json` 零 token 诊断子命令：建议直接采用

**六期执行计划**（已报备，每期 clean verify）：期1 `ORDER_MAP_ENTRIES_BY_KEYS` 一行修 C-08 + Usage.cachedTokens（R3）+ TokenEstimator（R1）+ debug 子命令 → 期2 `/context`（R2，消费 ComposedPrompt 明细分段）→ 期3 HistoryCompressor（R7：只折叠请求侧视图、滞回触发保护前缀缓存、配对构造性保证）→ 期4 R5 schema 瘦身 → 期5 实机验收 AC1~AC5+R10 → 期6（条件）D-04 元工具。

### ⏸ AC9 第二层 —— 阻塞在用户沙盒操作

真实执行 + 确认交互验证，需一次性环境。工具已备好：`tools/ac9/`（prepare-sandbox.ps1 / ac9-check.ps1 / ac9-commands.txt 67 条）；教程 `docs/ac9-manual-verification.md`。用户跑完把 `AC9-REPORT.txt` 贴回 → 归档 `docs/` 即完成。

### 📋 缺陷待办（用户 2026-10-12 决策：只记不修；修任何一条都需先报备）

| # | 缺陷 | 影响 | 修法方向 | 发现现场 |
|---|---|---|---|---|
| 1 | `ModelClient.buildRequestBody` 硬编码 `max_tokens`，Azure 前置模型组（如 `gpt-6-astra`）拒绝：HTTP 400 `litellm.BadRequestError … Use 'max_completion_tokens'` | **阻塞级**：用户当前默认模型用不了 | 按模型名/配置项在两个参数名间选择（可加 `agent.max_tokens_param_name` 配置键） | `docs/p0-3-r10-sampling/ac4-control-web-enabled.txt` 尾部有 400 原文 |
| 2 | `ChatRunner.start` 前置守卫 `notFoundModel()` 拒绝一切非别名值 → `else config.setModelName(modelOpt)` 死代码，CLI 无法指定真实模型名；且 `models:` 段 fast/pro 档案是裸名，内网路由 403 → `-m fast|-m pro` 不可用 | 别名机制名存实亡 | 调整守卫顺序：仅当"既非别名也非合法模型名"才报错 | AC4 报告附带发现 #2 |
| 3 | `ChatCommand` 恒打印「自定义配置: null」 | stdout 噪音（P1-3 范围） | 删除或改为有值才打印 | Phase 0 B5 记录，AC4 实机复现 |
| 4 | `execution.md`「同类工具连续失败 2 次即熔断」在轻量模型上遵循偏弱（AC4 前置实测换 4 种形态）；UNC 路径 `\\server\share` 不参与越界判定 | 体验/覆盖面小缺口 | P1/P2 调文案或宿主侧计数兜底；UNC 纳入 WorkspacePathAnalyzer | `docs/p0-3-r10-sampling/REPORT.md` 观测记录 |

### 🔜 未开始阶段（按规范依赖序）

- Phase 2：P1-1 日志与输出通道（C-06：logback stdout 治理）→ P1-2 非交互模式 → P1-3 终端适配（含待办 3；D-02=B 无 JLine）→ P1-4 `.skhermes` 遗留处置（D-01=A 已定：全删，含黑名单条目——注意需与 `SensitivePathGuard` 协调）→ P1-5 配置一致性（含 A22 `rag.enabled` 默认值议题，P0-3 D-5 明确留到此处）
- Phase 3：P2-1 自进化修复（`SkillMatcher` 类已保留待复用，见其 Javadoc）/ P2-2 RAG（D-05=B 嵌入式默认）/ P2-3 可观测 / P2-4 数据治理 / P2-5 技术债

## 四、灰区文件（本次未动，待用户拍板）

| 文件 | 情况 | 建议 |
|---|---|---|
| `thread-demo/ThreadDemo.java`（4.6KB，已跟踪） | 用户 10-06 的学习演示（讲 TrajectoryCollector 线程模型），与产品无关 | 若不再需要可 `git rm thread-demo/`（历史可恢复）；本次仅删了其 `.class` 编译残留 |
| `Jhermes/target/`（276MB，已忽略） | 构建产物；含 45.7MB 可执行 fat jar（AC9/AC4 复现与冒烟用） | 保留；需腾空间随时 `mvnw clean`（代价：下次构建全量重编 + jar 需重新 package） |
| `.github/modernize/java-upgrade/`（1.3KB，已忽略） | VS Code Java 现代化扩展生成的 hooks | 无害，扩展自管；删了扩展可能重建 |

## 五、文档地图

```
doc/          ← 不入库（.gitignore 有意为之）【绝对勿删】
  Jhermes智能体提示词.md        ← JH-SPEC-001 v2.0 规范原文（一切工作的依据）
  Jhermes智能体提示词.v1.bak.md ← v1 备份（v2 头部有 v1 更正表）
  项目流程自述版.md / 项目总体流程_优化版.md / 面试注意点.md / npm发布与版本维护教程.md
docs/         ← 已入库（验收证据链）
  baseline-report.md            ← Phase 0 基线（B2 token 分项、C-08 实证、D-07 冻结建议）
  ac9-layer1-report.txt         ← AC9 第一层 67/67 PASS（P0-2 修复后已重新生成）
  ac9-manual-verification.md    ← AC9 第二层保姆级教程（沙盒操作步骤）
  p0-3-r10-sampling/
    REPORT.md                   ← R10 抽样回归报告（legacy vs current 5 场景 + P0-2 缺陷取证）
    AC4-REPORT.md               ← AC4 实机验证报告（两层证据 + 配置改动哈希核对记录）
    questions.txt / r10run.ps1  ← 评测问题与运行器（复现用）
    runs/ ac4-*.txt             ← 原始输出证据（被两份报告引用）
```

## 六、新 agent 必读纪律与环境事实

**协作纪律（用户明确要求）：**
1. **改码前必须报备**：改哪些文件、怎么改、为什么，等用户确认再动手；每个 P0 大项先出实现级方案
2. 一需求一提交；每期 `clean verify` 全绿才进下一期；新增代码必须有测试；不得弱化既有安全机制
3. **不夹带范围外重构**：发现范围外缺陷 → 记录 + 问用户（先例：P0-2 缺陷在 R10 中发现，独立提交 `e198515` 而未混入 P0-3）
4. 动用户数据（`~/.jhermes/config.yaml`、MEMORY.md）：先备份 → 只改必要行 → 事后 SHA256 逐字节验证还原 → 显式报告实际执行了什么

**环境事实（踩过的坑）：**
- mvn 不在 PATH：`cd Jhermes; .\mvnw.cmd clean verify`；surefire `-Dtest=` 多类用**逗号**
- **改过主代码后必须 clean**：增量编译陈旧会让 `switch(enum)` 合成类缺失，报 `NoClassDefFoundError: Foo$1`（Errors 集中在无关测试类 = 构建陈旧，非代码问题）
- clean 后 fat jar 是 `Jhermes-2.1.0.jar`（`original-*.jar` 是 pre-shade 原件）
- 零 token 诊断：`security check "<命令>"`（分级取证）、`security check --file tools\ac9\ac9-commands.txt`（67 条期望门禁）、REPL 内 `/help`（能力表与注册表同源）
- 模型相关：可用名须完整模型组路径；`-m` 目前只认 `models:` 别名（待办 2）；`max_tokens` 400（待办 1）
- Windows 已知抖动：`SkillToolTimeoutTest` 偶发 AV 拦截 cmd.exe（error=5），单独重跑即绿；含中文 `.ps1` 须 UTF-8 BOM
- 长期记忆：用户机器上 Copilot 记忆文件 `/memories/repo/jhermes-facts.md`（项目事实）与 `/memories/session/jhermes-refactor-plan.md`（改造全程状态）与本文档同步维护，接手时优先读取

**测试基线演进**（防止误判"测试变少了"）：620（P0-2）→ 645 → 663 → 683 → 728 → 760 → **799**（P0-3 全期 + P0-2 缺陷修复）。

## 七、本次清理记录（2026-10-13）

**已删除**（均可恢复或为纯垃圾）：
| 文件 | 性质 | 恢复方式 |
|---|---|---|
| `Jhermes/dependency-reduced-pom.xml` | shade 插件构建副产物（每次 package 自动生成），删除前已 grep 确认全仓零引用 | `git checkout <hash> -- Jhermes/dependency-reduced-pom.xml`，或重新 package 自动生成 |
| `thread-demo/ThreadDemo.class` | javac 编译残留（已被 .gitignore 忽略） | 重新编译即得 |
| `%TEMP%` 下 28 个工作过程临时文件（verify 日志、测试计划草稿、pdiff/m.txt 等） | 本改造过程中的中间产物 | 无需恢复 |

**已修改**：`.gitignore` 登记 `dependency-reduced-pom.xml`（防再次误入库）+ 修复乱码注释「# Java ????」。

**明确保留**（不是遗漏）：`doc/`（规范，有意不入库）、`docs/` 全部（证据链）、`Jhermes/target/`（活跃构建产物，内含可执行 jar）、`.idea/` 与 `.github/modernize/`（IDE/扩展自管）。
`%TEMP%` 中保留两份安全备份：`config.yaml.ac4-base`（AC4 前配置基线）、`MEMORY.md.r10-bak`（R10 前记忆基线）——均已验证还原到位，留作双保险，可随时手删。
