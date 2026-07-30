package com.miniclaudecode.web;

import java.io.IOException;
import java.util.List;

/**
 * 可替换搜索 provider 的统一边界
 * readiness 检查与不可用提示由实现自行提供
 */
public interface SearchProvider {

    String name();

    boolean isReady();

    String unavailableHint();

    /**
     * @param query 非空搜索词
     * @param topK  期望结果数量，实现可按服务限制截断
     */
    List<SearchResult> search(String query, int topK) throws IOException;
}
