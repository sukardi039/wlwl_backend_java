package com.wlwl.backend.rpc;

/**
 * One implementation per PHP module directory (staff/, drill/, baseinfo/, ...).
 *
 * The default method bodies are deliberately EMPTY rather than throwing or
 * returning 404. That is a faithful port of the PHP: restdx.inc does
 * `include "get.php"` and most module directories have no get.php, which emits a
 * PHP warning, continues, and produces an empty 200. Callers get "no data"
 * rather than an error — which is exactly what the Vue client already handles.
 */
public interface ModuleHandler {

    /** Path segment, e.g. "staff" for /backend/staff/. */
    String module();

    default void onGet(RpcContext ctx) throws Exception { }

    default void onPost(RpcContext ctx) throws Exception { }

    default void onPut(RpcContext ctx) throws Exception { }

    default void onDelete(RpcContext ctx) throws Exception { }

    default void handle(RpcMethod method, RpcContext ctx) throws Exception {
        if (method == null) {
            // PHP: default: echo("No method specified!");
            ctx.text("No method specified!");
            return;
        }
        switch (method) {
            case GET -> onGet(ctx);
            case POST -> onPost(ctx);
            case PUT -> onPut(ctx);
            case DELETE -> onDelete(ctx);
        }
    }
}
