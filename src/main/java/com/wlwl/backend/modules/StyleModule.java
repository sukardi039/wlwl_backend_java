package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Port of style/ — styles are baseinfo rows with baseclass 'styles', left-joined to
 * the `style` table.
 *
 * Careful: baseclass 'styles' is SHARED with the driver-style profile entries, so the
 * listing returns rows with a null style half for those. That is what the PHP does.
 *
 * The insert branch tests `$qdata->style` while the update keys on `styleName`.
 */
@Component
public class StyleModule extends AbstractCatalogueModule {

    public StyleModule(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public String module() {
        return "style";
    }

    @Override
    protected String postSql() {
        return "select * from baseinfo b left join style p "
                + "on b.name = p.styleName where b.baseclass = 'styles'";
    }

    @Override
    protected List<Object> postArgs(JsonNode env) {
        return List.of();
    }

    @Override
    protected void upsert(JsonNode qdata) {
        String description = text(qdata, "styleDescription");

        if (!truthy(text(qdata, "style"))) {
            jdbc.update("insert into style (styleName, styleDescription) values (?, ?)",
                    text(qdata, "name"), description);
        } else {
            jdbc.update("update style set styleDescription = ? where styleName = ?",
                    description, text(qdata, "styleName"));
        }
    }

    @Override
    protected String putSelectSql() {
        return "select * from baseinfo b left join style p on b.name = p.styleName "
                + "where b.baseclass = 'styles' and styleName = ?";
    }

    @Override
    protected List<Object> putSelectArgs(JsonNode qdata) {
        return List.of(text(qdata, "name"));
    }
}
