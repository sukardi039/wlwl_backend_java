package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Port of environmental/ — environments are baseinfo rows (baseclass 'environment')
 * left-joined to the `environment` table.
 *
 * Note the asymmetry the PHP has: POST uses a LEFT JOIN, but the post-upsert re-read
 * uses an INNER JOIN (`from baseinfo b, environment p`). So a freshly inserted
 * environment only comes back if baseinfo already has a row with that name.
 */
@Component
public class EnvironmentalModule extends AbstractCatalogueModule {

    public EnvironmentalModule(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public String module() {
        return "environmental";
    }

    @Override
    protected String postSql() {
        return "select * from baseinfo b left join environment p "
                + "on b.name = p.environmentName where b.baseclass = 'environment'";
    }

    @Override
    protected List<Object> postArgs(JsonNode env) {
        return List.of();
    }

    @Override
    protected void upsert(JsonNode qdata) {
        String name = text(qdata, "name");
        String description = text(qdata, "environmentDescription");

        if (!truthy(text(qdata, "environmentName"))) {
            jdbc.update("insert into environment (environmentName, environmentDescription, "
                            + "orderImpact, moraleImpact, duration, applyAll, randomOne, "
                            + "lowMorale, lowEfficiency, highEfficiency) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    name, description,
                    intOf(qdata, "orderImpact"), intOf(qdata, "moraleImpact"),
                    doubleOf(qdata, "duration"), intOf(qdata, "applyAll"),
                    intOf(qdata, "randomOne"), intOf(qdata, "lowMorale"),
                    intOf(qdata, "lowEfficiency"), intOf(qdata, "highEfficiency"));
        } else {
            // NOTE: environmentName is the WHERE key and is not itself updated.
            jdbc.update("update environment set environmentDescription = ?, orderImpact = ?, "
                            + "moraleImpact = ?, duration = ?, applyAll = ?, randomOne = ?, "
                            + "lowMorale = ?, lowEfficiency = ?, highEfficiency = ? "
                            + "where environmentName = ?",
                    description, intOf(qdata, "orderImpact"), intOf(qdata, "moraleImpact"),
                    doubleOf(qdata, "duration"), intOf(qdata, "applyAll"),
                    intOf(qdata, "randomOne"), intOf(qdata, "lowMorale"),
                    intOf(qdata, "lowEfficiency"), intOf(qdata, "highEfficiency"),
                    text(qdata, "environmentName"));
        }
    }

    @Override
    protected String putSelectSql() {
        return "select * from baseinfo b, environment p where b.name = p.environmentName "
                + "and b.baseclass = 'environment' and environmentName = ?";
    }

    @Override
    protected List<Object> putSelectArgs(JsonNode qdata) {
        return List.of(text(qdata, "name"));
    }
}
