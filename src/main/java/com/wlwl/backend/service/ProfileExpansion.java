package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpRows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * The two lookup maps the profile modules attach to every row.
 *
 * Both are built with `while ($ros = mysqli_fetch_assoc(...)) { $exp[$code] = ...; }`
 * and then read WITHOUT an isset guard, so a class code that has no matching
 * profileclass row yields JSON null rather than an error. A HashMap lookup returning
 * null reproduces that exactly.
 *
 * {@code displayKeys} needs a second query because `displayKey` lives on baseinfo, not
 * on profileclass:
 *
 * <pre>
 *   select * from profileclass, baseinfo where profileName = name
 * </pre>
 *
 * which also drags in the whole baseinfo row set (a cross join filtered by name).
 */
@Service
public class ProfileExpansion {

    private final JdbcTemplate jdbc;

    public ProfileExpansion(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** $exp[profileClassCode] = profileClassDisplayName */
    public Map<String, String> displayNames() {
        Map<String, String> exp = new HashMap<>();
        for (Map<String, Object> ros : PhpRows.query(jdbc, "select * from profileclass")) {
            exp.put(string(PhpRows.get(ros, "profileClassCode")),
                    string(PhpRows.get(ros, "profileClassDisplayName")));
        }
        return exp;
    }

    /**
     * $exp[profileClassCode] = profileClassDisplayName and
     * $prf[profileClassCode] = displayKey, both from the join with baseinfo.
     *
     * Note the column is baseinfo.displayKey — the short code (e.g. "SK"), not the
     * profile class code.
     */
    public Expanded displayNamesAndKeys() {
        Map<String, String> exp = new HashMap<>();
        Map<String, String> prf = new HashMap<>();
        for (Map<String, Object> ros : PhpRows.query(jdbc,
                "select * from profileclass, baseinfo where profileName = name")) {
            String code = string(PhpRows.get(ros, "profileClassCode"));
            exp.put(code, string(PhpRows.get(ros, "profileClassDisplayName")));
            prf.put(code, string(PhpRows.get(ros, "displayKey")));
        }
        return new Expanded(exp, prf);
    }

    public record Expanded(Map<String, String> displayNames, Map<String, String> displayKeys) { }

    private static String string(Object v) {
        return v == null ? null : v.toString();
    }
}
