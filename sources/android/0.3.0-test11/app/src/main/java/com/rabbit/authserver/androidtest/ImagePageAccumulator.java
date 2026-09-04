package com.rabbit.authserver.androidtest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Collects virtualized viewer pages across multiple WebView snapshots for one job. */
final class ImagePageAccumulator {
    private final Map<Integer, LinkedHashSet<String>> pages = new TreeMap<>();

    void merge(List<WebAuthEngine.Page> snapshot) {
        for (WebAuthEngine.Page page : snapshot) {
            if (page.number < 1 || page.urls.isEmpty()) continue;
            pages.computeIfAbsent(page.number, ignored -> new LinkedHashSet<>()).addAll(page.urls);
        }
    }

    int size() {
        return pages.size();
    }

    List<WebAuthEngine.Page> snapshot() {
        List<WebAuthEngine.Page> result = new ArrayList<>(pages.size());
        for (Map.Entry<Integer, LinkedHashSet<String>> entry : pages.entrySet()) {
            result.add(new WebAuthEngine.Page(entry.getKey(), List.copyOf(entry.getValue())));
        }
        return List.copyOf(result);
    }
}
