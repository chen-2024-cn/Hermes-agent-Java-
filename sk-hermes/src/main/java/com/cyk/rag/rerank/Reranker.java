package com.cyk.rag.rerank;

import com.cyk.rag.search.SearchResult;
import java.util.List;

/**
 * 重排序接口（Phase 1 预留，不实现）。
 * 用于后续引入 Cross-Encoder 等二次排序模型。
 */
public interface Reranker {
    List<SearchResult> rerank(String query, List<SearchResult> candidates);
}
