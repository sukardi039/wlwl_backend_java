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

    /**
     * True only for modules whose index.php lets the query string override the HTTP
     * verb: {@code $method = strtoupper($_SERVER['REQUEST_METHOD']); if (isset($_GET['method'])) $method = strtoupper($_GET['method']);}
     *
     * In the PHP that is staffsignin/index.php only. Note it does NOT consult the
     * request BODY's method field the way etc/restdx.inc does — so a body "method"
     * is ignored for these modules.
     */
    default boolean allowsQueryStringMethodOverride() { return false; }

    /**
     * True for modules whose index.php switches on the HTTP verb ONLY and does not
     * include restdx.inc: registerdemo/ and signin/. The body's "method" field is
     * ignored there (they never read it), unlike every restdx module.
     */
    default boolean ignoresBodyMethod() { return false; }

    default void onGet(RpcContext ctx) throws Exception { }

    default void onPost(RpcContext ctx) throws Exception { }

    default void onPut(RpcContext ctx) throws Exception { }

    default void onDelete(RpcContext ctx) throws Exception { }

    /** staffsignin/index.php pseudo-verb: start the email/OTP auth handshake. */
    default void onGetAuth(RpcContext ctx) throws Exception { }

    /** staffsignin/index.php pseudo-verb: verify the OTP against the webhook. */
    default void onCheckAuth(RpcContext ctx) throws Exception { }

    /**
     * Dispatches on the verb STRING rather than the enum, because index.php has two
     * extra cases (GETAUTH, CHKAUTH) that are not HTTP verbs and cannot be represented
     * by the four-value enum. Unrecognised verbs echo "No method specified!" exactly
     * like the PHP's switch default branch.
     */
    default void handle(String verb, RpcContext ctx) throws Exception {
        if (verb == null || verb.isEmpty()) {
            // PHP: default: echo("No method specified!");
            ctx.text("No method specified!");
            return;
        }
        switch (verb) {
            case "GET" -> onGet(ctx);
            case "POST" -> onPost(ctx);
            case "PUT" -> onPut(ctx);
            case "DELETE" -> onDelete(ctx);
            case "GETAUTH" -> onGetAuth(ctx);
            case "CHKAUTH" -> onCheckAuth(ctx);
            default -> ctx.text("No method specified!");
        }
    }

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
