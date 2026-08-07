package com.miniclaudecode.mcp.resources;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 server 缓存 MCP resource 描述符
 * server 变更时支持整体失效，也可按 URI 精确失效
 */
public class McpResourceCache {
    private final Map<String, List<McpResourceDescriptor>> byServer = new ConcurrentHashMap<>();
    private final Set<String> staleServers = ConcurrentHashMap.newKeySet();
    private final Map<String, Set<String>> staleUrisByServer = new ConcurrentHashMap<>();

    public void put(String serverName, List<McpResourceDescriptor> resources) {
        if (serverName == null || serverName.isBlank()) {
            return;
        }
        byServer.put(serverName, resources == null ? List.of() : List.copyOf(resources));
        staleServers.remove(serverName);
        staleUrisByServer.remove(serverName);
    }

    public List<McpResourceDescriptor> get(String serverName) {
        if (serverName == null || isServerStale(serverName)) {
            return List.of();
        }
        return byServer.getOrDefault(serverName, List.of());
    }

    public List<McpResourceDescriptor> all() {
        List<McpResourceDescriptor> resources = new ArrayList<>();
        byServer.keySet().stream()
                .filter(server -> !isServerStale(server))
                .sorted()
                .forEach(server -> resources.addAll(byServer.getOrDefault(server, List.of())));
        resources.sort(Comparator
                .comparing(McpResourceDescriptor::serverName)
                .thenComparing(McpResourceDescriptor::uri));
        return resources;
    }

    public void invalidateServer(String serverName) {
        if (serverName != null && !serverName.isBlank()) {
            staleServers.add(serverName);
        }
    }

    public void invalidateResource(String serverName, String uri) {
        if (serverName == null || serverName.isBlank() || uri == null || uri.isBlank()) {
            return;
        }
        staleUrisByServer
                .computeIfAbsent(serverName, ignored -> ConcurrentHashMap.newKeySet())
                .add(uri);
    }

    public boolean isServerStale(String serverName) {
        return serverName != null && staleServers.contains(serverName);
    }

    public boolean isResourceStale(String serverName, String uri) {
        if (serverName == null || uri == null) {
            return false;
        }
        return staleUrisByServer.getOrDefault(serverName, Set.of()).contains(uri);
    }

    public void markResourceFresh(String serverName, String uri) {
        Set<String> staleUris = staleUrisByServer.get(serverName);
        if (staleUris != null) {
            staleUris.remove(uri);
        }
    }
}
