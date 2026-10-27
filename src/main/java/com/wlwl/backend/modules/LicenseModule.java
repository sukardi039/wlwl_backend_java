package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of license/ — issued licences, read from `license_view` (license JOIN
 * licensebatch, so each row also carries its batchDesc).
 *
 * The `mod` filter is an OR: it matches either the batch or the package, which is how
 * the UI asks "all licences in this batch" and "all licences for this customer" with
 * the same endpoint.
 */
@Component
public class LicenseModule extends AbstractCatalogueModule {

    public LicenseModule(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public String module() {
        return "license";
    }

    @Override
    protected String postSql() {
        return "select * from license_view";
    }

    @Override
    protected List<Object> postArgs(JsonNode env) {
        return new ArrayList<>();
    }

    /** Directions differ from its siblings: the OR filter has to be spliced in. */
    @Override
    public void onPost(com.wlwl.backend.rpc.RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String mod = text(env, "mod");

        String sql = "select * from license_view";
        List<Object> args = new ArrayList<>();
        if (truthy(mod)) {
            // PHP: where batchId = '<mod>' or packageId = '<mod>'
            sql = sql + " where batchId = ? or packageId = ?";
            args.add(mod);
            args.add(mod);
        }
        Integer limit = new com.wlwl.backend.rpc.RpcEnvelopeReader(env).limitOrNull();
        if (limit != null) {
            sql = sql + " limit ?";
            args.add(limit);
        }

        respond(ctx, com.wlwl.backend.db.PhpRows.query(jdbc, sql, args.toArray()),
                ctx.queryStringLength());
    }

    /** This module is read-only; there is no put.php. */
    @Override
    protected void upsert(JsonNode qdata) {
        throw new UnsupportedOperationException("license/ has no put.php");
    }

    @Override
    protected String putSelectSql() {
        return postSql();
    }

    @Override
    protected List<Object> putSelectArgs(JsonNode qdata) {
        return new ArrayList<>();
    }
}
