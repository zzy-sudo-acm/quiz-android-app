# AI 修复接口契约

## 概述

AI 修复是题库导入流程的**兜底阶段**。仅当本地规则解析（`OriginalQuestionParser`）无法识别某个题块时，才会调用 DeepSeek API 进行格式修复。

## 核心原则

| 原则 | 说明 |
|------|------|
| **AI 不负责凭空生成答案** | AI 不能编造题目、选项或答案。所有内容必须来自原文 |
| **只修复结构异常** | 将格式错乱的题块整理为规范 JSON，不改变题目内容 |
| **原文无答案 → `answer = null`** | 原文确实没有答案信息时，禁止推测，必须返回 null |
| **修复失败 → 人工确认** | 校验失败的题块直接跳过，在导入结果中明确报告 |
| **所有输出必须通过 JSON Schema 校验** | 详见下方 [JSON Schema 校验规则](#json-schema-校验规则) |

## 接口定义

### 请求

```
POST https://api.deepseek.com/chat/completions
Authorization: Bearer <用户配置的 API Key>
Content-Type: application/json
```

```json
{
  "model": "deepseek-chat",
  "stream": false,
  "temperature": 0.0,
  "max_tokens": 2048,
  "messages": [
    {
      "role": "user",
      "content": "<修复 Prompt，包含原题纯文本>"
    }
  ]
}
```

### 输入：failedBlock

输入是 `OriginalQuestionParser` 解析失败的**单个题块纯文本**，长度 ≤ 500 字符。

示例（格式异常的题块）：

```
1.下列哪一个命令可以修改设备名字为huawei? rename huawei B.sysname huawei C.do name huawei D.hostname huawei 答案：B  VRp基础
```

> 注意：选项 A/B/C/D 挤在同一行，无换行分隔，本地正则无法拆分选项标记。

### 输出：修复后的 JSON

#### 成功（可识别的完整题目）

```json
{
  "type": "single",
  "question": "下列哪一个命令可以修改设备名字为huawei?",
  "options": [
    {"key": "A", "text": "rename huawei"},
    {"key": "B", "text": "sysname huawei"},
    {"key": "C", "text": "do name huawei"},
    {"key": "D", "text": "hostname huawei"}
  ],
  "answer": ["B"],
  "explanation": "",
  "knowledge": "VRP基础"
}
```

#### 无法识别（不是完整题目）

```
null
```

> 返回字面量 `null`（纯文本，非 JSON 字符串），表示此段无法识别为一道完整题目。

### 字段说明

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 是 | `"single"` / `"multiple"` / `"truefalse"` |
| `question` | string | 是 | 题干原文，不得改写 |
| `options` | array | 是 | 选项列表，每项含 `key` 和 `text` |
| `options[].key` | string | 是 | 单字母 A-H |
| `options[].text` | string | 是 | 选项原文，不得改写 |
| `answer` | array | 条件 | 答案 key 列表，如 `["A"]` 或 `["A","B"]`。**原文确实无答案时可省略此字段** |
| `explanation` | string | 否 | 解析，可为空字符串 |
| `knowledge` | string | 否 | 知识点，可为空字符串 |

## JSON Schema 校验规则

`JsonValidator.parseRepairedQuestion()` 执行以下严格校验，**任一条件不满足则整题丢弃**：

```
1. 输入不能为空
2. 输入为字面量 "null" → 返回 null（合法跳过）
3. 必须是可解析的 JSON 对象（自动剥离 Markdown 代码块）
4. question 不能为空或纯空白
5. options 不能为空，每项 text 不能为空
6. 非判断题至少需要 2 个选项
7. answer 不能为空（原文无答案的题目已在阶段一被过滤）
8. answer 中每个 key 必须存在于 options 的 key 集合中
9. 判断题必须仅有 A/B 两个选项，文本须为 对/正确/√ 与 错/错误/×
```

### 校验失败示例

| 场景 | AI 返回 | 校验结果 |
|------|---------|----------|
| 选项 key 不匹配 | `answer: ["X"]`，options 无 X | 失败：答案包含不存在的选项 |
| 题干为空 | `question: ""` | 失败：题干为空 |
| 判断题多选项 | 判断题有 4 个选项 | 失败：判断题必须仅 A/B |
| 选项文本为空 | `{"key":"A","text":""}` | 失败：选项文本为空 |
| 编造答案 | 原文无答案，AI 推测了答案 | 阶段一已区分：原文确无答案时 `answer = null`，校验器直接拒绝 |

## 错误处理

```
API 调用异常
    │
    ├── 网络超时 / HTTP 非 200
    │   └── 记录日志 "第 N 段 API 调用失败：<原因>"
    │       └── 跳过该段
    │
    ├── 响应为空
    │   └── 跳过该段
    │
    └── JSON 解析/校验失败
        └── 跳过该段，不静默丢弃（计入 skippedCount）
```

用户在导入结果中可看到：
- `本地识别 N 道`：阶段一成功的题目数
- `API 修复 M 道`：阶段二成功的题目数
- `跳过 K 段`：两个阶段都失败的题块数

## 与阶段一（规则解析）的分工

| | 阶段一：规则解析 | 阶段二：AI 修复 |
|------|------|------|
| **输入** | 完整 DOCX 文本 | 单个 failedBlock |
| **方法** | 正则匹配 | LLM 理解 |
| **速度** | 即时 | API 调用延迟 |
| **成本** | 免费 | 消耗 API token |
| **能力** | 结构化格式 | 非结构化/混乱格式 |
| **安全性** | 确定性 | 需严格校验 |

AI 修复仅作为规则解析的**补充**，不是替代。规则能处理的题目不经过 AI。
