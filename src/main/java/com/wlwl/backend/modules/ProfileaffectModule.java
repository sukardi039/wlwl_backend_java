package com.wlwl.backend.modules;

import com.wlwl.backend.rpc.RpcContext;
import com.wlwl.backend.service.ProfileExpansion;
import com.wlwl.backend.service.ProfileSeeder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Port of profileaffect/ (post.php + put.php).
 *
 * Same table and same post.php as profileimpact, but put.php writes the AFFECT
 * columns (skillsAffect / moraleAffect / efficiencyAffect / affectMsg) instead of the
 * impact ones. Both column sets live on the same `profileimpact` row, which is why
 * these two modules cannot be tested in isolation from each other.
 */
@Component
public class ProfileaffectModule extends AbstractProfileImpactModule {

    public ProfileaffectModule(JdbcTemplate jdbc, ProfileSeeder seeder,
                               ProfileExpansion expansion) {
        super(jdbc, seeder, expansion);
    }

    @Override
    public String module() {
        return "profileaffect";
    }

    @Override
    protected String columnPrefix() {
        return "Affect";
    }

    @Override
    public void onPut(RpcContext ctx) throws Exception {
        super.onPut(ctx);
    }
}
