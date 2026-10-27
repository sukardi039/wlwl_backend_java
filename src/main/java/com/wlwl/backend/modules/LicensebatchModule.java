package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.wlwl.backend.db.PhpUniqid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Port of licensebatch/ — licence batches from `batchlicense_view`.
 *
 * Two things differ from its siblings:
 *  - the listing filters on `$data->mod` as an agentId (not a `package` field);
 *  - PUT has NO update branch at all. It always mints a new batchId and inserts, so
 *    editing an existing batch from the UI creates another one.
 */
@Component
public class LicensebatchModule extends AbstractCatalogueModule {

    public LicensebatchModule(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public String module() {
        return "licensebatch";
    }

    @Override
    protected String postSql() {
        String sql = "select * from batchlicense_view";
        // The filter is decided in postArgs; the SQL text must match.
        return sql;
    }

    @Override
    protected List<Object> postArgs(JsonNode env) {
        return new ArrayList<>();
    }

    /** Needs the WHERE clause when `mod` is set, so the base flow is overridden. */
    @Override
    public void onPost(com.wlwl.backend.rpc.RpcContext ctx) throws Exception {
        JsonNode env = ctx.envelope();
        String sql = "select * from batchlicense_view";
        List<Object> args = new ArrayList<>();

        String mod = text(env, "mod");
        if (truthy(mod)) {
            sql = sql + " where agentId = ?";
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

    @Override
    protected void upsert(JsonNode qdata) {
        // No branch: every PUT inserts.
        jdbc.update("insert into licensebatch (batchId, agentId, batchDesc, issueDate, "
                        + "availableTill, licenseVolume) values (?, ?, ?, ?, ?, ?)",
                PhpUniqid.next(), text(qdata, "agentId"), text(qdata, "batchDesc"),
                text(qdata, "issueDate"), text(qdata, "availableTill"),
                intOf(qdata, "licenseVolume"));
    }

    @Override
    protected String putSelectSql() {
        return "select * from batchlicense_view where agentId = ?";
    }

    @Override
    protected List<Object> putSelectArgs(JsonNode qdata) {
        return List.of(text(qdata, "agentId"));
    }
}
