# 智能识别接口与校验契约

> 文件名为兼容旧链接保留。当前实现不是“本地失败题块的 AI 修复”，而是独立的完整文档智能识别路径。

## 调用前提

智能识别只有在以下条件同时满足时才会调用模型 API：

1. 用户主动选择“智能识别混乱格式”。
2. DOCX 已在本机读取并生成来源块。
3. 已通过 Android 加密存储保存有效的 API Key。
4. 用户在数据与费用说明后再次确认调用。

选择文件、查看文档摘要和标准格式导入均不会触发 API。

## API 请求

```http
POST https://api.deepseek.com/chat/completions
Authorization: Bearer <用户配置的 API Key>
Content-Type: application/json
```

模型由设置页档位选择：

- 快速模式：`deepseek-v4-flash`
- 高质量模式：`deepseek-v4-pro`

请求为非流式、`temperature = 0`，当前响应上限为 `8192` tokens。请求内容是 `SmartModelRequest` 的 JSON 投影及约束 Prompt，包含：

- 阶段（边界识别或结构化）和批次 ID
- 每个来源块的 ID、顺序、类型、原文和字符范围
- Word 自动编号信息
- 表格行列坐标
- 图片引用标识（不含图片二进制）
- 第二阶段的候选来源和集中答案区来源

不会发送原始 DOCX 压缩包、关系文件或图片二进制。图片内容可能决定答案时，报告会提示人工确认。

## 长文档与覆盖规则

- 每个非空来源块必须进入边界识别，不允许先用本地正则筛掉“看起来成功”的内容。
- 长文档按估算大小切片，相邻批次保留来源块重叠。
- 单个超长来源块按字符范围切片，并在请求中保留 `charStart`/`charEnd`。
- 一个来源块可包含零道、一道或多道题；响应的 `questions` 必须使用数组。
- 标题、章节说明、集中答案区、无法确认内容和不支持结构都要显式分类。

## 两阶段响应

### 边界识别

第一阶段返回严格 JSON：

```json
{
  "questions": [
    {
      "tempId": "q1",
      "sourceIds": ["p3", "p4"],
      "originalQuestionNumber": 1
    }
  ],
  "answerSections": [{"sourceIds": ["p100"]}],
  "nonQuestionSourceIds": ["p1"],
  "unsupportedSourceIds": [],
  "unresolvedSourceIds": []
}
```

只有字段完整且可逐字追溯时，第一阶段才可以同时返回完整题目结构；否则只返回候选边界，交由第二阶段处理。

### 结构化

第二阶段可以一次返回多道题。每道完整题必须包含：

```json
{
  "tempId": "q1",
  "sourceIds": ["p3", "p4", "p100"],
  "originalQuestionNumber": 1,
  "type": "single",
  "question": "题干原文",
  "options": [
    {"key": "A", "text": "选项 A 原文"},
    {"key": "B", "text": "选项 B 原文"}
  ],
  "answer": ["B"],
  "explanation": null,
  "knowledge": null,
  "questionSource": ["p3"],
  "optionSources": {"A": ["p4"], "B": ["p4"]},
  "answerSource": ["p100"],
  "explanationSource": [],
  "knowledgeSource": []
}
```

模型不得改写题干、补造选项、根据常识纠正原题或猜答案。没有明确答案来源时应放入 `unresolvedSourceIds`，而不是伪造成功结果。

## 本地强校验

模型返回不直接入库。`SmartImportPipeline` 至少执行以下检查：

1. 返回根节点必须是可解析的 JSON 对象，其中 `questions` 必须是数组并可包含多道题。
2. 所有 `sourceId` 必须存在于本次文档。
3. 题干、选项、解析和知识点必须能在声明的来源原文中追溯。
4. 答案必须有 `answerSource`，且能从答案原文中解析出来。
5. 答案 key 必须存在于选项集合；单选/判断只能有一个答案，多选至少两个答案。
6. 题型必须是 `single`、`multiple` 或 `truefalse`。
7. 选项 key 和内容不得重复；题干与选项组合重复的题目会被拒绝。
8. 图片只按来源关系挂载，不允许模型猜测图片内容。

校验失败不会静默丢弃：原文、来源 ID、原因码和说明会写入导入报告。

## 失败与重试语义

常见稳定原因码包括：

| 原因码 | 含义 |
|---|---|
| `API_KEY_MISSING` | 未配置可用密钥 |
| `API_REQUEST_FAILED` | 网络、HTTP 或服务调用失败 |
| `API_INVALID_JSON` | 响应不是约定 JSON |
| `API_RETURNED_NULL` | 模型明确返回空结果 |
| `API_HALLUCINATED_CONTENT` | 字段无法在来源原文中追溯 |
| `MISSING_ANSWER` | 缺少明确答案或答案来源 |
| `ANSWER_NOT_IN_OPTIONS` | 答案不属于选项 |
| `DUPLICATE_QUESTION` | 识别结果重复 |
| `SOURCE_NOT_COVERED` | 无法确认来源归属 |

本次导入任务只缓存成功的模型响应。相同成功请求可复用以避免重复计费；失败响应不会缓存，因此重试仍会真实调用服务。报告分别记录是否使用过 API 和逻辑模型批次数；单个批次内部因 429、5xx 或网络故障发生的传输重试不重复计入该字段。

## 密钥与日志约束

- API Key 不写入普通 SharedPreferences、导入报告、应用日志或仓库文件。
- 加密存储不可用时，设置页拒绝保存 Key，并清除旧版本可能遗留的明文值。
- 密钥设置文件排除在云备份和设备迁移之外。
- 连接测试只发送固定的最小消息，不包含题库文字。
