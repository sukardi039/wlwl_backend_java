package com.wlwl.backend.rpc;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Module path segment -> handler. Equivalent to the PHP's per-directory index.php lookup. */
@Component
public class ModuleRegistry {

    private final Map<String, ModuleHandler> handlers = new LinkedHashMap<>();

    public ModuleRegistry(List<ModuleHandler> discovered) {
        for (ModuleHandler h : discovered) {
            ModuleHandler previous = handlers.put(h.module(), h);
            if (previous != null) {
                throw new IllegalStateException(
                        "Duplicate ModuleHandler for module '" + h.module() + "': "
                                + previous.getClass().getName() + " and " + h.getClass().getName());
            }
        }
    }

    /** Null means "no such directory" — the gateway answers 404, as IIS would. */
    public ModuleHandler find(String module) {
        return handlers.get(module);
    }

    public java.util.Set<String> modules() {
        return java.util.Collections.unmodifiableSet(handlers.keySet());
    }
}
