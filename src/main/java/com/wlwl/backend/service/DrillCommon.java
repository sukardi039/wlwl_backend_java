package com.wlwl.backend.service;

import com.wlwl.backend.db.PhpArray;
import com.wlwl.backend.db.PhpJson;
import com.wlwl.backend.db.PhpRows;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Port of drill/commonLib.php — the shared state maths. Being built up function by
 * function in dependency order; this file currently holds the helpers that INIT and
 * ENVIRONMENT need.
 */
public final class DrillCommon {

    private DrillCommon() {
    }

    /**
     * writeHistory(): append one row to content.history.
     *
     * The PHP indexes with sizeof($cont['history']) — i.e. it appends — so a plain append is
     * equivalent. Ten fields, in this order.
     */
    public static void writeHistory(PhpArray cont, Object step, Object day, Object staff,
                                    Object staffExp, Object actType, Object act,
                                    Object skills, Object morale, Object eff, Object msg) {
        PhpArray entry = new PhpArray();
        entry.put("step", step);
        entry.put("day", day);
        entry.put("staff", staff);
        entry.put("staffExp", staffExp);
        entry.put("actType", actType);
        entry.put("act", act);
        entry.put("skills", skills);
        entry.put("morale", morale);
        entry.put("eff", eff);
        entry.put("msg", msg);
        cont.child("history").append(entry);
    }

    /**
     * initMemberValues(): clears the per-turn accumulators and decays the "listen" counter.
     *
     * Two rules worth naming:
     *   - a member who has messages but has NOT read them loses one listen point; one who
     *     did read them gets the full tolerance back
     *   - the message reset is guarded by `$readMsg !== 0`, a STRICT check, so the string
     *     "0" also triggers the reset; only an integer 0 holds messages back
     */
    public static void initMemberValues(PhpArray content, Map<String, Object> params) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");
        Object tolerance = params.get("ignored_tolerance_days");

        for (int c = 1; c <= maxMembers; c++) {
            Object raw = members.get(c);
            if (!(raw instanceof PhpArray m)) continue;
            if (!truthy(m.get("staffName"))) continue;

            m.put("skillsAdded", 0);
            m.put("moraleAdded", 0);
            m.put("efficiencyAdded", 0);
            m.put("added", 0);
            m.put("emotion", 0);
            m.put("addedAvg", 0);

            if (PhpJson.num(m.get("msgCount")) > 0) {
                if (PhpJson.num(m.get("readMsg")) == 0) {
                    m.put("listen", PhpJson.num(m.get("listen")) - 1);
                } else {
                    m.put("listen", tolerance);
                }
            }
            if (!isStrictlyZero(m.get("readMsg"))) {
                m.put("msgCount", 0);
                m.put("message", new PhpArray());
            }
        }

        content.child("staffMsg").put("count", 0);
        content.child("bossMsg").put("count", 0);
    }

    /**
     * noMoreStaff(): true only when NO member slot 1..maxMembers has a name.
     * (The PHP also calls dbg() inside this loop with an undefined $content, which is a
     * no-op there — passing by reference creates a local — so it is not reproduced.)
     */
    public static boolean noMoreStaff(PhpArray ct) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(ct, "control", "maxMembers"));
        PhpArray members = ct.child("members");
        for (int c = 1; c <= maxMembers; c++) {
            Object raw = members.get(c);
            if (raw instanceof PhpArray m && truthy(m.get("staffName"))) {
                return false;
            }
        }
        return true;
    }

    /** dbg(): append "Y-m-d H:i:s head: body" to content.debug. */
    public static void dbg(PhpArray ct, String head, Object body) {
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        ct.child("debug").append(stamp + " " + head + ": " + body);
    }

    /** readParams(): the whole params_int table as key -> value. */
    public static Map<String, Object> readParams(JdbcTemplate jdbc) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<Map<String, Object>> rows = PhpRows.query(jdbc, "select * from params_int");
        for (Map<String, Object> row : rows) {
            params.put(String.valueOf(PhpRows.get(row, "key")), PhpRows.get(row, "value"));
        }
        return params;
    }

    /** currentMembers(): how many slots hold a name. */
    public static int currentMembers(PhpArray content) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");
        int v = 0;
        for (int c = 1; c <= maxMembers; c++) {
            if (named(members, c)) v++;
        }
        return v;
    }

    /** anyOne(): a RANDOM named member. rand() is inclusive, so the range is 1..count. */
    public static Pick anyOne(PhpArray content, java.util.Random rnd) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");
        int count = currentMembers(content);
        if (count <= 0) return new Pick(null, null);

        int v = 1 + rnd.nextInt(count);                     // PHP rand(1, count)
        while (!named(members, v) && v < maxMembers) v++;   // PHP: staffName < 1 && v < max
        PhpArray m = member(members, v);
        return m == null ? new Pick(null, null) : new Pick(m.get("staffName"), m.get("staffExp"));
    }

    /** lowMorale(): the member with the lowest moraleValue that still has a name. */
    public static Pick lowMorale(PhpArray content) {
        return extreme(content, "moraleValue", true);
    }

    /** lowEfficiency(): lowest efficiencyValue. */
    public static Pick lowEfficiency(PhpArray content) {
        return extreme(content, "efficiencyValue", true);
    }

    /** highEfficiency(): highest efficiencyValue. */
    public static Pick highEfficiency(PhpArray content) {
        return extreme(content, "efficiencyValue", false);
    }

    /**
     * Shared body of lowMorale / lowEfficiency / highEfficiency — they differ only in which
     * field they read and the direction of the comparison.
     *
     * The PHP opens with `while ($members[$sv]['staffName'] < 1) $sv++;` and NO bound, so an
     * all-empty team would spin forever reading undefined keys. Here the scan stops at
     * maxMembers+1 and returns an empty Pick; a hang is not a behaviour worth reproducing.
     */
    private static Pick extreme(PhpArray content, String field, boolean lowest) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");

        int sv = 1;
        while (sv <= maxMembers + 1 && !named(members, sv)) sv++;
        if (sv > maxMembers) return new Pick(null, null);

        PhpArray first = member(members, sv);
        Object bestName = first.get("staffName");
        Object bestExp = first.get("staffExp");
        double best = PhpJson.num(first.get(field));

        for (int v = sv + 1; v <= maxMembers; v++) {
            if (!named(members, v)) continue;
            PhpArray m = member(members, v);
            double val = PhpJson.num(m.get(field));
            // strict < / > : PHP never replaces on an equal value, so the FIRST one wins ties
            if (lowest ? val < best : val > best) {
                bestName = m.get("staffName");
                bestExp = m.get("staffExp");
                best = val;
            }
        }
        return new Pick(bestName, bestExp);
    }

    /** memberWhere(): the slot holding this name, or -1. */
    public static int memberWhere(PhpArray content, Object staffName) {
        int maxMembers = (int) PhpJson.num(PhpJson.path(content, "control", "maxMembers"));
        PhpArray members = content.child("members");
        String wanted = staffName == null ? "" : staffName.toString();
        for (int c = 1; c <= maxMembers; c++) {
            PhpArray m = member(members, c);
            if (m == null) continue;
            Object name = m.get("staffName");
            if (name != null && wanted.equals(name.toString())) return c;
        }
        return -1;
    }

    /** A member plus the staffExp that travels with it (PHP returns one and passes the other by reference). */
    public record Pick(Object name, Object exp) {
    }

    /**
     * PHP compares `staffName > 0`. On PHP 5.x a non-numeric name casts to 0 and this would be
     * FALSE for every member; on PHP 7+/8 it is a string comparison against "0". The app runs
     * PHP 7+, where a real name is greater and an empty string is not - so "has a name" is the
     * faithful reading, and "0" is excluded because "0" > "0" is false.
     */
    private static boolean named(PhpArray members, int c) {
        PhpArray m = member(members, c);
        if (m == null) return false;
        Object name = m.get("staffName");
        if (name == null) return false;
        String s = name.toString();
        return !s.isEmpty() && !"0".equals(s);
    }

    private static PhpArray member(PhpArray members, int c) {
        Object v = members.get(c);
        return v instanceof PhpArray a ? a : null;
    }

    // ------------------------------------------------------------- helpers

    /** PHP truthiness for the values that reach these functions. */
    private static boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.doubleValue() != 0;
        String s = v.toString();
        return !s.isEmpty() && !"0".equals(s);
    }

    /** PHP `$v !== 0` — strict, so the string "0" is NOT zero. */
    private static boolean isStrictlyZero(Object v) {
        if (v instanceof Long || v instanceof Integer || v instanceof Short || v instanceof Byte) {
            return ((Number) v).doubleValue() == 0;
        }
        return false;
    }
}
