-- ============================================================
-- pgvector 初始化脚本（容器首次创建数据目录时自动执行）
--
-- 只做一件事：预建 vector 扩展。
-- 表结构（rag_documents）与索引由 Java 侧 PgVectorStore.initSchema()
-- 负责创建（CREATE TABLE IF NOT EXISTS），保持"代码是唯一事实源"，
-- 避免这里的 DDL 与代码漂移后互相打架。
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;
