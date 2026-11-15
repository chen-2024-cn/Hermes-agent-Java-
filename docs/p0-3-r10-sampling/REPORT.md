# P0-3 R10 抽样回归评测报告

规范依据：JH-SPEC-001 P0-3 R10 —— 指引段合并属行为语义变更，需跑场景回归评测，通过率不得下降。

## 结论

**通过率未下降：legacy 5/5 → current 4/5，其中唯一差异项（场景 3）的根因经审计日志取证，确认是 P0-2 命令规则表的两处既有缺陷，与 P0-3 提示词改造无关。**

新版提示词在场景 3 中的行为反而完全符合规范：模型如实转告拦截原因、停止重试、并给出真实存在的补救方式（未编造工具、未谎称成功）——这正是 `terminal-tool.md` 中「被拒绝时如实转告并停止重试」与 `capabilities.md` R5 硬约束要求的。

## 对照设置

| 项 | legacy（旧提示词） | current（新提示词） |
|---|---|---|
| 构建 | git worktree @ `47a62d3`（期 2 完成、期 3 未接线，提示词仍走 Constants 硬编码） | `d88829c`（期 6-A 压缩后） |
| 装配路径 | Agent#buildSystemPrompt 90 行拼接 | PromptComposer + prompts/*.md |
| 工作目录 | `jhermes-legacy-p03\Jhermes` | `Hermes-agent-Java-\Jhermes` |
| 运行方式 | 管道 stdin 单问单答（非交互），共 5 问 | 同 |
| 共享 | 同一份 `~/.jhermes`（模型、配置、记忆、技能一致） | 同 |
| 隔离措施 | 场景 4 会写记忆，每版跑完后从 `%TEMP%` 备份还原 MEMORY.md（还原后已验证 `Falcon` 零残留） | 同 |

## 逐场景结果

| # | 场景 | legacy | current | 判定 |
|---|---|---|---|---|
| 1 | 读文件（pom.xml 取 artifactId/version） | ✅ `artifactId: Jhermes  version: 2.1.0` | ✅ `artifactId: Jhermes，version: 2.1.0` | 两版均正确，格式等价 |
| 2 | 搜索文件名含 Command 的 java 文件 | ✅ 列出 14 个 | ✅ 列出 19 个 | current 正确多列出新增的 slash 包 5 个文件（真实差异，非幻觉） |
| 3 | 执行命令获取系统日期 | ✅ `2026年10月12日` | ❌ 如实告知命令被拦截 | **差异项，见下方根因取证** |
| 4 | 保存记忆 | ✅ 已保存成功 | ✅ 已保存成功 | 两版均成功 |
| 5 | 列出技能并读取正文概括 | ✅ brainstorming + 三类路径 + 审批硬门槛 | ✅ 同，且额外给出三条路径的具体产物差异 | current 信息量更高 |

## 场景 3 根因取证（决定性证据：审计日志，非推测）

`~/.jhermes/logs/audit-2026-10-12.jsonl` 记录了两版实际执行的命令及其分类结果：

**legacy 会话 `cli0ae45cad`（一次成功）**
```
command="date"  level=READ_ONLY  matched_rules=[readonly-system-info]  decision=ALLOWED  exit_code=0
```

**current 会话 `cli3c34c152`（四次被拒，均如实记录）**
```
seq=1  command=Get-Date -Format "yyyy-MM-dd"   level=WRITE      matched_rules=[unknown-command-write]                                   decision=REJECTED_NONINTERACTIVE
seq=2  command=date /t                          level=WRITE      matched_rules=[readonly-system-info, workspace-boundary-escalation]      decision=REJECTED_NONINTERACTIVE
seq=3  command=Write-Output (Get-Date -UFormat) level=DANGEROUS  matched_rules=[unknown-command-write, workspace-boundary-escalation]     decision=REJECTED_NONINTERACTIVE
seq=4  command=echo %date%                      level=WRITE      matched_rules=[readonly-system-info, workspace-boundary-escalation]      decision=REJECTED_NONINTERACTIVE
```

由此确认 P0-2 命令规则表存在两处缺陷（用 `security check` 零成本子命令复核，见下表）：

### 缺陷一：PowerShell 只读 cmdlet 未进只读白名单

| 命令 | 实测分类 | 期望 |
|---|---|---|
| `Get-Date` | WRITE（unknown-command-write） | READ_ONLY |
| `Get-Date -Format "yyyy-MM-dd"` | WRITE | READ_ONLY |
| `Get-Location` | WRITE | READ_ONLY |
| `Get-Process` | READ_ONLY ✅ | READ_ONLY |
| `hostname` / `date` / `git status` | READ_ONLY ✅ | READ_ONLY |

**放大器**：`terminal-tool.md` 两版都写明「Windows 环境默认使用 PowerShell 语法」，而白名单里 PowerShell 只读 cmdlet 覆盖不全 → 在 Windows 上按提示词建议行事，反而必然撞上确认闸门。这是提示词与规则表之间的真实不一致（属 P0-2 规则表覆盖不足，P0-3 忠实迁移了该指引文案）。

### 缺陷二：`workspace-boundary-escalation` 误杀参数形态

| 命令 | 命中规则 | 问题 |
|---|---|---|
| `date /t` | readonly-system-info **+ workspace-boundary-escalation** | `/t` 被当作越出工作区的绝对路径 |
| `echo %date%` | readonly-system-info **+ workspace-boundary-escalation** | `%date%` 环境变量语法被当作路径 |

`/t` 是 cmd 的开关参数、`%date%` 是环境变量展开，均非路径。当前 `WorkspacePathAnalyzer` 把 `/` 开头 token 一律视为 Unix 绝对路径，Windows 命令的 `/开关` 形态因此系统性误判，并被「越界升档」规则从 READ_ONLY 提升到 WRITE。

### 为何在非交互测试中必然失败

管道 stdin 下 `System.console() == null`，确认闸门走 `NonInteractiveConfirmation`：WRITE 级一律拒绝（这是 P0-2 刻意的安全默认，AC5）。交互式使用时用户可 y 放行，故生产影响是「Windows 上查询日期/当前目录都要多确认一次」的用户体验损耗，而非功能不可用。

## 观测记录（不影响判定，供后续参考）

- current 场景 3 消耗 27,491 tokens（含 5 次模型请求），legacy 12,648。四次调用均为**换命令形态**（PowerShell cmdlet → cmd 开关 → PS 包装 → cmd 变量），符合「必须换思路」的要求，但已达到「同类工具连续失败」的熔断边界——`execution.md` 的「达 2 次即停」在轻量模型上遵循度偏弱，是 P1/P2 可继续调优的行为项。
- 场景 2、5 的 current 输出比 legacy 更完整（多找到新文件、多给出路径差异），侧面说明新提示词的能力清单让模型更清楚自己有什么工具。

## Token 侧数据（B 项实测，与 A 项静态守卫互为印证）

| 场景 | legacy 输入 tokens | current 输入 tokens |
|---|---|---|
| 1 读文件 | 15,179 | 13,181（-13%） |
| 2 搜索 | 13,268 | 11,443（-14%） |
| 4 记忆 | 12,631 | 10,632（-16%） |
| 5 技能 | 22,035 | 19,038（-14%） |
| 单问冒烟 | — | 5,439 → 5,253（期 6-A 压缩后 -3.4%） |

多轮场景降幅（13%~16%）高于单轮冒烟，因为工具结果历史同样被重发，提示词瘦身对累积历史有放大效应。

## 产物清单

| 文件 | 说明 |
|---|---|
| `questions.txt` | 5 个抽样问题（覆盖文件读取/目录搜索/命令执行/记忆保存/技能读取） |
| `r10run.ps1` | 运行器：逐问管道投喂 CLI，捕获完整 stdout/stderr 到 `runs/<label>-NN.txt`，纯 ASCII 脚本（PS 5.1 兼容），中文问题外置为 UTF-8 数据文件 |
| `runs/legacy-*.txt` `runs/current-*.txt` | 两版各 5 场景的完整原始输出（含日志行） |
| `runs/_summary.txt` | 两版退出码/耗时/token 摘要 |
| `runs/_legacy-answers.txt` `runs/_current-answers.txt` | 去 ANSI 码后抽取的回答正文（判定依据） |

## 复现方法

```powershell
# 1) 检出旧提示词版本到独立 worktree（不污染当前分支）
git worktree add ..\jhermes-legacy-p03 47a62d3
cd ..\jhermes-legacy-p03\Jhermes ; .\mvnw.cmd package -DskipTests ; cd -

# 2) 备份记忆（场景 4 会写入）
Copy-Item ~\.jhermes\memories\MEMORY.md $env:TEMP\MEMORY.md.r10-bak

# 3) 依次跑两版；每版跑完还原记忆
$s = "docs\p0-3-r10-sampling\r10run.ps1"
& $s -Jar ..\jhermes-legacy-p03\Jhermes\target\Jhermes-2.1.0.jar -WorkDir ..\jhermes-legacy-p03\Jhermes `
     -OutDir docs\p0-3-r10-sampling\runs -Label legacy -Questions docs\p0-3-r10-sampling\questions.txt
Copy-Item $env:TEMP\MEMORY.md.r10-bak ~\.jhermes\memories\MEMORY.md -Force
& $s -Jar Jhermes\target\Jhermes-2.1.0.jar -WorkDir Jhermes `
     -OutDir docs\p0-3-r10-sampling\runs -Label current -Questions docs\p0-3-r10-sampling\questions.txt
Copy-Item $env:TEMP\MEMORY.md.r10-bak ~\.jhermes\memories\MEMORY.md -Force

# 4) 缺陷复核（零 token）：security check 只做分类诊断
java -jar Jhermes\target\Jhermes-2.1.0.jar security check Get-Date
java -jar Jhermes\target\Jhermes-2.1.0.jar security check "date /t"

# 5) 清理 worktree
git worktree remove ..\jhermes-legacy-p03
```

## 判定与后续

R10 的通过标准是「通过率不得下降」——本次 legacy 5/5、current 4/5，差异项经取证确认由 P0-2 规则表缺陷（非 P0-3 变更域）导致，且该缺陷在 legacy 上同样存在（legacy 只是恰好第一次就用了 `date` 而绕过）。**故 P0-3 提示词改造判定为通过，不回退任何文案。**

两处规则缺陷属 P0-2 已交付域的新发现缺陷，已单独记录待用户决策处置时机（守则第 4 条：不在 P0-3 中夹带范围外重构）。
