package com.wlwl.backend.modules;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Port of task/ — tasks are baseinfo rows with baseclass 'tasks', left-joined to the
 * `task` table. Same shape as style/: insert keys on `name`, update on `taskName`.
 */
@Component
public class TaskModule extends AbstractCatalogueModule {

    public TaskModule(JdbcTemplate jdbc) {
        super(jdbc);
    }

    @Override
    public String module() {
        return "task";
    }

    @Override
    protected String postSql() {
        return "select * from baseinfo b left join task p "
                + "on b.name = p.taskName where b.baseclass = 'tasks'";
    }

    @Override
    protected List<Object> postArgs(JsonNode env) {
        return List.of();
    }

    @Override
    protected void upsert(JsonNode qdata) {
        String description = text(qdata, "taskDescription");

        if (!truthy(text(qdata, "taskName"))) {
            jdbc.update("insert into task (taskName, taskDescription) values (?, ?)",
                    text(qdata, "name"), description);
        } else {
            jdbc.update("update task set taskDescription = ? where taskName = ?",
                    description, text(qdata, "taskName"));
        }
    }

    @Override
    protected String putSelectSql() {
        return "select * from baseinfo b left join task p on b.name = p.taskName "
                + "where b.baseclass = 'tasks' and taskName = ?";
    }

    @Override
    protected List<Object> putSelectArgs(JsonNode qdata) {
        return List.of(text(qdata, "name"));
    }
}
