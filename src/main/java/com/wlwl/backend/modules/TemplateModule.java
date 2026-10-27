package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Port of template/ — templates are baseinfo rows (baseclass 'template') left-joined
 * to the `template` table. Same LEFT-JOIN-for-POST / INNER-JOIN-for-re-read asymmetry
 * as environmental/.
 */
@Component
public class TemplateModule extends AbstractCatalogueModule {

    public TemplateModule(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public String module() {
        return "template";
    }

    @Override
    protected String postSql() {
        return "select * from baseinfo b left join template p "
                + "on b.name = p.templateName where b.baseclass = 'template'";
    }

    @Override
    protected List<Object> postArgs(JsonNode env) {
        return List.of();
    }

    @Override
    protected void upsert(JsonNode qdata) {
        String description = text(qdata, "templateDescription");
        double duration = doubleOf(qdata, "duration");
        int staff = intOf(qdata, "numberOfStaff");
        int days = intOf(qdata, "numberOfDays");
        int events = intOf(qdata, "environmentEvents");
        int recruits = intOf(qdata, "recruits");
        String report = text(qdata, "report");

        if (!truthy(text(qdata, "templateName"))) {
            jdbc.update("insert into template (templateName, templateDescription, duration, "
                            + "numberOfStaff, numberOfDays, environmentEvents, recruits, report) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                    text(qdata, "name"), description, duration, staff, days, events,
                    recruits, report);
        } else {
            // templateName is the WHERE key and is not updated.
            jdbc.update("update template set duration = ?, templateDescription = ?, "
                            + "recruits = ?, numberOfStaff = ?, numberOfDays = ?, "
                            + "environmentEvents = ?, report = ? where templateName = ?",
                    duration, description, recruits, staff, days, events, report,
                    text(qdata, "templateName"));
        }
    }

    @Override
    protected String putSelectSql() {
        return "select * from baseinfo b, template p where b.name = p.templateName "
                + "and b.baseclass = 'template' and templateName = ?";
    }

    @Override
    protected List<Object> putSelectArgs(JsonNode qdata) {
        return List.of(text(qdata, "name"));
    }
}
