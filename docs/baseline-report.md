# Jhermes 改造 Phase 0 基线报告

| 项目 | 内容 |
|---|---|
| 依据 | `doc/Jhermes智能体提示词.md`（JH-SPEC-001 v2.0）§7 |
| 日期 | 2026-10-09 |
| 执行环境 | Windows / Temurin JDK 21.0.12 / Maven 3.9.12（`~/.m2/wrapper` 缓存发行版，`mvn` 不在 PATH） |
| 基线起点 | commit `5bca8b0` → 基线快照 `fd3d4c3` → B1 修复 `60100e7` → B7 矩阵 `5936956` |
| 度量环境 | 用户真实配置（`~/.jhermes/config.yaml`）：pgvector@5433 可用、api_key 已配、模型经内网路由 `ai-router.anker-in.com` → `anthropic/v_model/deepseek-v4-pro`；cwd=`Jhermes/` 模块目录 |

---

## B1 / B8：构建与测试基线

- 首跑 `mvn verify`：**193 条用例，1 失败**（`ConsoleUiTest.assistantPrefixShouldContainVisibleMarker`）。
  根因：改名 sk-hermes→Jhermes 后提示符实为 `◆ Jhermes`（`Constants.CLI_NAME`，无冒号），测试仍硬编码旧品牌 `"Hermes:"`。
  处置：按 P0-1 规则「修测试，不得跳过」，断言改为动态引用 `Constants.CLI_NAME`（commit `60100e7`）。
- 修复后复验：**BUILD SUCCESS，193/193，约 31s**；fat jar `Jhermes-1.0-SNAPSHOT.jar` 45.6MB（与发布工作流 45MB 预期一致，瘦身过滤器生效）。
- B7 新增后全量复验：**287/287 通过（50s）**。
- 测试盘点（B8）：24 个测试类文件（含 1 个辅助类 `InMemoryVectorStore`）+ 新增 `CommandPolicyBaselineTest` = 25 文件 / 24 类，287 条用例。无外部服务依赖的单测占绝大多数；`PgVectorStoreTest`、`RagEngineEndToEndTest` 等标 `integration` 组默认排除（Testcontainers）。surefire 已配 `excludedGroups=integration`。**无 Mockito、无 PR/测试 CI、发布工作流 `-DskipTests`**（与规范 §3.1 一致，P0-1 处置）。

## B2：Token 基线（真实 API 校准）

| 组合 | 系统提示词(字符) | tools JSON(字符) | 工具数 | 实测 prompt_tokens | 字符/token | cached_tokens |
|---|---|---|---|---|---|---|
| rag开×终端开（满配） | 8,402 | 6,952 | 16 | **6,150** | 2.50 | 0 |
| rag开×终端关 | 7,704 | 6,237 | 15 | 5,514 | 2.52 | 0 |
| rag关×终端开 | 7,923 | 5,412 | 12 | 5,313 | 2.51 | **2,048**（同体重试命中） |
| rag关×终端关（最小） | 7,225 | 4,697 | 11 | 4,678 | 2.55 | **2,048** |

满配系统提示词分项（字符，恒注入除 TERMINAL_TOOL 外全部段）：

| 段 | 字符 | 约合 token | 备注 |
|---|---|---|---|
| 记忆快照（MEMORY+USER.md） | 2,524 | ~1,010 | 随用户数据变化 |
| **「Available Tools」文本清单** | **1,548** | **~620** | **与 tools 数组纯重复，P0-3 R3 直接删除** |
| EXECUTION_DISCIPLINE_GUIDANCE | 1,266 | ~506 | 最大指引段，T2 重写候选 |
| RAG_GUIDANCE | 862 | ~345 | rag 关时仍注入（P0-3 R2 条件化） |
| TERMINAL_TOOL_GUIDANCE | 477 | ~191 | 唯一条件注入段 |
| PLATFORM_HINTS["cli"] | 357 | ~143 | 与 TERMINAL_OUTPUT 重复（P0-3 R3 合并） |
| TOOL_USE_ENFORCEMENT | 290 | ~116 | 与 EXECUTION_DISCIPLINE 语义重叠 |
| WEB_SEARCH_GUIDANCE | 239 | ~96 | web 工具集恒注册，可留 |
| TERMINAL_OUTPUT_GUIDANCE | 154 | ~62 | 并入 cli 段 |
| IDENTITY | 127 | ~51 | 保留 |
| SKILLS_GUIDANCE | 114 | ~46 | 指向记忆工具是错的（P0-3 R2 改指 skill_manage） |
| MEMORY_GUIDANCE | 91 | ~36 | 保留 |
| SESSION_SEARCH_GUIDANCE | 58 | ~23 | 能力不存在，P0-3 R2 删除 |
| 工作目录快照 | ~300 | ~120 | 本环境 cwd 较小；50 条上限，大项目会显著变大 |

工具数验证：16/15/12/11 与规范 §3.2「固定 11 + 条件 run_command + 4 rag」完全一致。

## B3：请求体跨进程一致性（C-08 推断验证）

**结论：推断成立，已证实为 bug。** 同配置两个独立 JVM 进程的请求体等长（15,727B）但**5,792 字节不同**；首个差异位于 `tools[0].function` 内部键序（run1 `name→description→parameters` vs run2 `name→parameters→description`）。根因为各工具 `register()` 用 `Map.of`/`HashMap` 构建 schema，`ImmutableCollections` 的迭代顺序受每 JVM 随机 SALT 影响。

**连带证据（缓存命中机制）**：同 JVM 内重试发送字节相同的请求体，网关返回 `prompt_tokens_details.cached_tokens=2048`；跨进程（前缀不一致）则为 0。供应商**确认支持** OpenAI 标准 `cached_tokens` 字段（另有 `cache_write_tokens`），P0-4 R3 字段兼容方案可锁定。P0-4 R8（tools 有序序列化）为缓存命中的前置条件，优先级证实。

## B4：终端实测矩阵

自动化部分（重定向到文件）：banner/中文/符号正常写入 UTF-8 文件、**无 ANSI 转义码残留**（非 TTY 检测已生效）。
**缺口**：非 TTY 重定向时 banner 仍然打印（P1-3 R5 要求不打印）——记录为现状。
人工待办（需你在三种终端交互式各跑一次并截图）：①cmd.exe（代码页 936）②PowerShell 5.1 ③Windows Terminal；观察 `╭ ▶ ◆ ⚙ ✨ 📝` 符号与中文渲染。

## B5：stdout 污染盘点（`Jhermes > out.txt`，实测）

stdout 5,176B，**stderr 0B**（所有日志全走 stdout）。逐条对照附录 A.3：

| # | 污染源 | 实测内容 | 状态 |
|---|---|---|---|
| 1 | logback ConsoleAppender→stdout | `[WARN] .skhermes 与 .jhermes 并存`、`[INFO] Loaded 1 skills`、`[INFO] 终端工具 run_command 已注册`、`[INFO] RAG schema initialized`、`[INFO] 检测到输入流结束` | 证实，P1-1 R1/R2/R4 |
| 2 | ChatCommand 恒打印 | `自定义配置: null`（`Jhermes chat` 路径实测重现；根命令路径无此行） | 证实，P1-1/P1-5 |
| 3 | ChatRunner 三行状态 | `模型: …` `温度: 0.7` `流式: 关闭` | 证实，P1-3 R8 并入状态行 |
| 4 | WebSearchTool 3 处 println + 演示 main | 静态证实（本次未触发搜索） | P1-1 R6 |
| 5 | Constants.migrateLegacyData println | 本次走 WARN 通道未触发 println（两目录并存分支不打 println，仅迁移成功才打） | 随 P1-4=D-01 A 删除 |
| 6 | TerminalTool `[run #N]`、Agent 提示符/goodbye/用量行 | 属 UI 输出，走 stdout 合理 | P1-1 R3 迁入 Ui |
| 7 | banner 重定向时仍打印 | 证实 | P1-3 R5 |

## B6：分发渠道清点与 D-01 前提核对

- 渠道①：GitHub Releases（`release-upload.yml` 手动触发，产物 `hermes.jar`，仓库 `chen-2024-cn/Hermes-agent-Java-`）。
- 渠道②：npm 壳包 `j-hermes`（`publishConfig.registry=npmjs.org`，postinstall 从 Releases 下载 jar）。
- **npm 注册表实查**：`j-hermes` 仅有 `2.1.0` 一个版本；`sk-hermes`、`skhermes` 均 404 → **npm 渠道从未分发过 `.skhermes` 时代版本**。
- git 标签：`v2.0.1`（改名版）与 `v2.1.0`；仓库内无任何 sk-hermes 时代的 Release 痕迹。
- **D-01 前提成立**（旧版未经 npm 外发；GitHub Releases 侧无 sk 时代 tag）。结合你的决定 **D-01=方案 A（全量删除，含 SensitivePathGuard 黑名单条目）**。
- ⚠️ 执行提醒：你本机 `~/.skhermes` 与 `~/.jhermes` 并存（实测 WARN）。方案 A 删除守卫条目后，旧目录若残留有效密钥将不再受保护——P1-4 执行前请你人工核对 `~/.skhermes/config.yaml`，确认无有效密钥后备份并删除该目录。
- 未勘察项清零：`npm-package/`（壳包 2 脚本）、`docker/`（pgvector 初始化 SQL）、`Dockerfile`/`docker-compose.yml`（RAG 起库）、README（含 skhermes 迁移描述，P1-4 R3 删除）、`.github/modernize/`（工具生成物，与运行时无关）。

## B7：命令策略基线矩阵

新增 `CommandPolicyBaselineTest`（commit `5936956`，入库作为 P0-2 回归基线）：82 条用例 + 12 条配额守卫，纯字符串分类不执行命令。结果：

| 现状分类 | 条数 | 说明 |
|---|---|---|
| 灾难正则拦截 | 23 | P0-2 后必须仍拦（AC2 回归保障） |
| 敏感路径拦截 | 3 | `.ssh`、`.aws/credentials`、外发密钥 |
| **危险但漏过** | **44** | PowerShell 灾难等价物（`Remove-Item -Recurse -Force C:\`、`Format-Volume`、`Stop-Computer`）、git 破坏 6 条、注册表/服务 7 条、提权 5 条、下载执行 7 条、绕过手法 5 条（变量展开/base64/EncodedCommand/引号拆分/别名）、路径越界 4 条、敏感缺口 4 条（`.env`/`*.pem`/策略文件/审计目录） |
| **误杀** | **2** | 引号内含危险字样的 `grep`/`echo` 被整串正则误拦（P0-2 分段器「引号内是数据」修复，AC3） |
| 正常放行 | 10 | `rm -rf ./target`、`git push origin main`、`ls`、`mvn verify` 等 |

## 与规范文档不符之处（逐条登记，以代码为准）

1. §3.1「测试类 23 个，用例总数待 Phase 0 统计」→ 实测既有 193 条（含 1 条失败已修）+ B7 新增 94 条 = 287 条全绿。
2. 既有测试在基线上**并非全绿**（ConsoleUiTest 过期断言）→ 已修复并提交，未跳过未排除。
3. §3.1 pom 版本 `1.0-SNAPSHOT` → 产物名 `Jhermes-1.0-SNAPSHOT.jar`，与 `Constants.VERSION=2.1.0`、发布 tag `v2.1.0` 三处不一致（P0-1 R2 处置，证据已采集）。
4. C-08【推断】→ 升级为【源码+实测】证实（B3）。
5. 用户实际 pgvector 端口 5433（默认配置模板是 5432）；模型为内网路由的 `anthropic/v_model/deepseek-v4-pro`，与默认模板 `deepseek-v4-pro` 直连不同——「你是谁」实测 token 与旧版日志 6,837 同数量级（现 6,150，差异来自工作目录快照与记忆快照规模）。
6. `doc/` 目录被 `.gitignore` 忽略（仓库现状），本报告与规范文档均不入 git。**需要你决策**：P0-1 是否将 `doc/`（及后续 `doc/adr/`、基线报告）纳入版本控制（规范 CC-6 要求文档同步，建议放行 `doc/` 入库）。
7. `mvn` 不在系统 PATH（构建依赖 `~/.m2/wrapper` 缓存发行版）→ 建议 P0-1 顺带提交 Maven Wrapper（`mvnw.cmd`/`mvnw` + `.mvn/wrapper/`），使本地与 CI 构建自包含。

## D-07 数值冻结建议

基线：满配「你是谁」= **6,150 prompt_tokens**。逐项推演（按 2.5 字符/token）：

- T1（去重+条件化，语义不变）：删 Available Tools 清单 −620、删 SESSION_SEARCH −23、合并 cli/TERMINAL_OUTPUT −80、RAG 条件化（关时 −345）→ 满配 rag-on 仅降到 ~5,430（88%）。**T1 单独达不到 65%**。
- T1+ 指引段重写（EXECUTION_DISCIPLINE/TOOL_USE 语义合并压半 ≈ −310；SKILLS/WEB_SEARCH 精简 ≈ −60）→ ~5,050（82%）。
- T2（schema 精简，tools 6,952→约 4,000 字符）≈ −1,180 → **~3,870（63%）**，达成 ≤65% 需 T1+T2 联动。
- Stretch「首轮 ≤2,000」**不可行**：16 工具的合法 schema 下限即 ~1,600 tokens，加核心行为指引后 >2,000。除非启用 D-04 B（元工具按需加载，牺牲一轮往返）。

**建议冻结**：T1（含指引重写）≤ 基线 80%；T2 ≤ 基线 55%（3,400）；放弃 2,000 stretch 或降为「rag-off+终端关最小配置 ≤3,000」另行评估。记忆快照（~1,010）属用户数据，不计入瘦身目标但纳入 `/context` 可观测。

## 风险与建议（进入 Phase 1 前）

1. **P0-2 优先做分段器测试**：44 条漏过用例全部依赖「引号感知分段+PowerShell 规则」，是本期最大工程量（L）。
2. P0-4 数值目标若维持 65% 硬指标，必须在 T1 中同时重写 EXECUTION_DISCIPLINE（占提示词 15%）——涉及行为语义变更，需按 R10 建立 15 场景回归评测集后再动。
3. CI 引入后 Windows/Linux 双平台首跑可能暴露路径大小写/换行差异测试（CC-1），预留修复时间。
4. `~/.skhermes` 人工清理是 D-01=A 的前置动作（防凭据失去守卫后裸奔）。

## Phase 0 产出清单

- 本报告（`doc/baseline-report.md`）
- B1/B7 commit：`fd3d4c3`（基线快照）、`60100e7`（测试修复）、`5936956`（82 条基线矩阵）
- 探针（B2/B3）：临时测试类已按「不入库」要求**删除**；度量原始数据在 `Jhermes/target/baseline-probe/`（构建产物目录，不入库）
- B5/B6 证据：`%TEMP%\b5-out.txt`、`b5-out2.txt`（本机留存）

**待负责人确认后即可进入 Phase 1（P0-1 → P0-2 → P0-3 → P0-4，P0-5 并行）。**
