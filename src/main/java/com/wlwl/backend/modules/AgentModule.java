package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import com.wlwl.backend.db.PhpUniqid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Port of agent/ — agents come from `agentlicense_view`, not a base table.
 *
 * The INSERT mints `agentId = uniqid()` when the client sends none, then re-reads
 * `agentlicense_view where agentId = <new id>`. Whether that returns anything depends
 * entirely on the view's definition, which is NOT in the repo (the schema dump is
 * missing). With the inner-join fixture used in tests a brand-new agent has no licence
 * row yet, so the re-read is empty and the response is 204 — the insert still happened.
 * If production's view LEFT JOINs, the same code returns 200 with a row. No guessing in
 * the port: it runs the query the PHP runs.
 */
@Component
public class AgentModule extends AbstractCatalogueModule {

    public AgentModule(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public String module() {
        return "agent";
    }

    @Override
    protected String postSql() {
        return "select * from agentlicense_view";
    }

    @Override
    protected List<Object> postArgs(JsonNode env) {
        return List.of();
    }

    @Override
    protected void upsert(JsonNode qdata) {
        String agentId = text(qdata, "agentId");

        if (!truthy(agentId)) {
            agentId = PhpUniqid.next();
            jdbc.update("insert into agent (agentId, agentName, availableTill, active) "
                            + "values (?, ?, ?, ?)",
                    agentId, text(qdata, "agentName"),
                    text(qdata, "availableTill"), intOf(qdata, "active"));
        } else {
            jdbc.update("update agent set agentName = ?, availableTill = ?, active = ? "
                            + "where agentId = ?",
                    text(qdata, "agentName"), text(qdata, "availableTill"),
                    intOf(qdata, "active"), agentId);
        }
    }

    @Override
    protected String putSelectSql() {
        return "select * from agentlicense_view where agentId = ?";
    }

    @Override
    protected List<Object> putSelectArgs(JsonNode qdata) {
        return List.of(text(qdata, "agentId"));
    }
}
