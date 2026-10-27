package com.wlwl.backend.modules;

import com.wlwl.backend.rpc.RpcContext;
import com.wlwl.backend.service.ProfileExpansion;
import com.wlwl.backend.service.ProfileSeeder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Port of profileimpact/ (post.php + put.php).
 *
 * Reads and seeds the shared `profileimpact` matrix and writes the IMPACT columns.
 * post.php is byte-identical to profileaffect/post.php; only the column prefix
 * differs, so both share {@link AbstractProfileImpactModule}.
 */
@Component
public class ProfileimpactModule extends AbstractProfileImpactModule {

    public ProfileimpactModule(JdbcTemplate jdbc, ProfileSeeder seeder,
                               ProfileExpansion expansion) {
        super(jdbc, seeder, expansion);
    }

    @Override
    public String module() {
        return "profileimpact";
    }

    @Override
    protected String columnPrefix() {
        return "Impact";
    }

    /** Unused override point kept explicit: impact writes via the shared onPut. */
    @Override
    public void onPut(RpcContext ctx) throws Exception {
        super.onPut(ctx);
    }
}
