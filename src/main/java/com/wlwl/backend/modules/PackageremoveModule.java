package com.wlwl.backend.modules;

import com.wlwl.backend.rpc.ModuleHandler;
import com.wlwl.backend.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Port of packageremove/ — the 7-line housekeeping module.
 *
 * <pre>
 *   delete from company where availableTill &lt; CURDATE();
 *   http_response_code(204);
 * </pre>
 *
 * Two things to preserve exactly:
 *  - it answers **204 unconditionally**, even if the DELETE fails (the PHP never looks
 *    at the result of mysqli_query), so a failure here must not become a 500;
 *  - it has no response body whatsoever, and the frontend treats it as fire-and-forget.
 */
@Component
public class PackageremoveModule implements ModuleHandler {

    private static final Logger log = LoggerFactory.getLogger(PackageremoveModule.class);

    private final JdbcTemplate jdbc;

    /** Test hook: rows deleted by the last run. */
    private volatile Integer lastDeleted;

    public PackageremoveModule(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String module() {
        return "packageremove";
    }

    @Override
    public void onPost(RpcContext ctx) {
        try {
            lastDeleted = jdbc.update("delete from company where availableTill < CURDATE()");
            log.info("packageremove: deleted {} expired company row(s)", lastDeleted);
        } catch (Exception e) {
            // PHP: mysqli_query returns false and is never inspected -> still 204.
            log.error("packageremove: delete failed (PHP would still answer 204)", e);
        }
        ctx.status(204);
    }

    public Integer lastDeleted() {
        return lastDeleted;
    }
}
