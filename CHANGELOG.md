# Changelog

## 0.1.6-furnace-rebrand (2026-05)

- 项目更名为 QuizForge
- 完善 DOCX 导入的两阶段解析流水线：本地规则解析 + AI 修复兜底
- AI 修复接口增加严格 JSON Schema 校验
- 修复判断题答案推断逻辑
- 优化行内多选项拆分（OptionTextSplitter）
- 补充安全策略：API Key 仅本地存储，不做硬编码

## 0.1.5

- 新增 DeepSeek API 流式接口
- 新增错题本模式
- 答题进度持久化（Room + QuizProgressDao）

## 0.1.4

- 新增 DOCX 图片提取与题目关联
- 新增 SettingsStore 管理用户配置

## 0.1.3

- 新增随机刷题模式
- 优化答题界面交互

## 0.1.2

- 新增判断题支持
- 新增多选题支持

## 0.1.1

- 新增题库管理（创建、删除、切换）
- Room 数据库集成

## 0.1.0

- 初始版本
- 基础刷题功能：单选题、顺序刷题
- 默认题库（网络互联选择题）
- Jetpack Compose UI
