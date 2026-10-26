package com.wlwl.backend.rpc;

import java.util.Locale;

/** The verb set restdx.inc dispatches on. */
public enum RpcMethod {
    GET, POST, PUT, DELETE;

    /** Returns null for anything unrecognised; PHP's switch falls through to its default branch. */
    public static RpcMethod of(String raw) {
        if (raw == null) return null;
        switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "GET": return GET;
            case "POST": return POST;
            case "PUT": return PUT;
            case "DELETE": return DELETE;
            default: return null;
        }
    }
}
