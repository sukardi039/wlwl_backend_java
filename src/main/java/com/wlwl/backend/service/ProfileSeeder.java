package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpRows;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Port of the "create the full array" seeding block that appears in
 * profileaffect/post.php, profileimpact/post.php and profilesens/post.php.
 *
 * The tables hold one row per COMBINATION of the three profile classes — skills,
 * morale, efficiency — so an empty table means "this matrix has never been built".
 * The seeding code enumerates the cartesian product restricted to one profile per
 * position:
 *
 * <pre>
 *   foreach class where profileName == profiles[0]
 *     foreach class where profileName == profiles[1]
 *       foreach class where profileName == profiles[2]
 *         INSERT ...
 * </pre>
 *
 * where {@code profiles[]} is the list of names from `baseinfo where baseclass =
 * 'profiles'`, INDEXED IN FETCH ORDER. If fewer than three profile names exist,
 * {@code $profiles[1]} / {@code $profiles[2]} are undefined, compare loosely against
 * nothing, and NO rows are inserted — the caller then answers 204.
 *
 * All statements are bound here; the PHP interpolated everything, which is the one
 * class of change the port makes deliberately (see PORTING_NOTES §2).
 */
@Service
public class ProfileSeeder {

    private static final String PROFILE_CLASS_QUERY =
            "select * from profileclass order by profileName, profileClassCode";

    private final JdbcTemplate jdbc;

    public ProfileSeeder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** True when baseinfo has any baseclass='profiles' row ($pn truthy in the PHP). */
    public boolean hasProfiles() {
        return !profileNames().isEmpty();
    }

    /** PHP: $profiles[$pn++] = $row['name'] — fetch order, no sorting. */
    public List<String> profileNames() {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> row : PhpRows.query(jdbc,
                "select * from baseinfo where baseclass = 'profiles'")) {
            Object name = PhpRows.get(row, "name");
            names.add(name == null ? null : name.toString());
        }
        return names;
    }

    /** PHP: if (!$result->num_rows) { ...seed... } for profileimpact. */
    public void seedImpactIfEmpty(String impactClass) {
        Integer existing = jdbc.queryForObject(
                "select count(*) from profileimpact where impactClass = ?",
                Integer.class, impactClass);
        if (existing != null && existing > 0) return;

        List<String[]> combos = combinations();
        for (String[] c : combos) {
            // profileImpactKey is NULL: it is an AUTO_INCREMENT column in the schema.
            jdbc.update("insert into profileimpact (profileImpactKey, impactClass, "
                            + "skillsClass, moraleClass, efficiencyClass, "
                            + "skillsImpact, moraleImpact, efficiencyImpact, impactMsg, "
                            + "skillsAffect, moraleAffect, efficiencyAffect, affectMsg) "
                            + "values (NULL, ?, ?, ?, ?, 0.00, 0.00, 0.00, '', "
                            + "0.00, 0.00, 0.00, '')",
                    impactClass, c[0], c[1], c[2]);
        }
    }

    /** PHP: if ($result->num_rows === 0) { ...seed... } for profilesens. */
    public void seedSensitivityIfEmpty() {
        Integer existing = jdbc.queryForObject(
                "select count(*) from profilesens", Integer.class);
        if (existing != null && existing > 0) return;

        for (String[] c : combinations()) {
            jdbc.update("insert into profilesens (profileSensitivityKey, "
                            + "skillsClass, moraleClass, efficiencyClass, "
                            + "skillsSensitivity, moraleSensitivity, efficiencySensitivity) "
                            + "values (NULL, ?, ?, ?, 0.00, 0.00, 0.00)",
                    c[0], c[1], c[2]);
        }
    }

    /**
     * The triple loop, flattened. Each element is {skillsClassCode, moraleClassCode,
     * efficiencyClassCode} in the order the three nested foreach loops would emit.
     */
    private List<String[]> combinations() {
        List<String[]> classes = new ArrayList<>();
        for (Map<String, Object> row : PhpRows.query(jdbc, PROFILE_CLASS_QUERY)) {
            classes.add(new String[]{
                    text(PhpRows.get(row, "profileName")),
                    text(PhpRows.get(row, "profileClassCode"))
            });
        }

        List<String> profiles = profileNames();
        String want0 = at(profiles, 0);
        String want1 = at(profiles, 1);
        String want2 = at(profiles, 2);

        List<String[]> out = new ArrayList<>();
        for (String[] p0 : classes) {
            if (!phpLooseEquals(p0[0], want0)) continue;
            for (String[] p1 : classes) {
                if (!phpLooseEquals(p1[0], want1)) continue;
                for (String[] p2 : classes) {
                    if (!phpLooseEquals(p2[0], want2)) continue;
                    out.add(new String[]{p0[1], p1[1], p2[1]});
                }
            }
        }
        return out;
    }

    /** PHP array access on a missing index yields null. */
    private static String at(List<String> list, int i) {
        return i < list.size() ? list.get(i) : null;
    }

    /** PHP '==' between two strings/nulls: null matches null and "". */
    private static boolean phpLooseEquals(String actual, String expected) {
        if (expected == null) return actual == null || actual.isEmpty();
        return expected.equals(actual);
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }
}
