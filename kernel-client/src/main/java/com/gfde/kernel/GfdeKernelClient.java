package com.gfde.kernel;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public interface GfdeKernelClient {
  Optional<Map<String, Object>> get(String type, String id);

  String put(String type, String id, Map<String, Object> payload);

  List<Map<String, Object>> scan(String type, String field, Object equals, int limit);

  static GfdeKernelClient inMemory() { 
    return new InMemoryKernel(); 
  }

  class InMemoryKernel implements GfdeKernelClient {
    
    private final Map<String, Map<String, Map<String, Object>>> store = new ConcurrentHashMap<>();
    private final Map<String, Long> version = new ConcurrentHashMap<>();

    @Override 
    public Optional<Map<String, Object>> get(String type, String id) {
      return Optional.ofNullable(store.getOrDefault(type, Map.of()).get(id));
    }

    @Override 
    public String put(String type, String id, Map<String, Object> payload) {
      store.computeIfAbsent(type, t -> new ConcurrentHashMap<>()).put(id, payload);
      long v = version.merge(type+":"+id, 1L, Long::sum);
      payload.put("_ver", v);
      return Long.toString(v);
    }

    @Override 
    public List<Map<String, Object>> scan(String type, String field, Object eq, int limit) {
      return store.getOrDefault(type, Map.of()).values().stream()
        .filter(m -> Objects.equals(m.get(field), eq))
        .limit(limit)
        .toList();
    }
  }
}
