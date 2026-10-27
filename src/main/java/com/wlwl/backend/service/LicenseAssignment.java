package com.wlwl.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.wlwl.backend.db.PhpRows;
import com.wlwl.backend.db.PhpUniqid;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Port of the two functions at the bottom of backenduser/put.php:
 * {@code get_licenseBatch()} and {@code update_license()}.
 *
 * These are written as free functions in the PHP and are duplicated verbatim inside
 * registerdemo/put.php (same SQL, same branches). Factoring them into one service
 * means that duplication is resolved once instead of twice — the behaviour is
 * identical, and the registerdemo port can reuse it as-is.
 *
 * The rule the whole thing encodes, which is not obvious from the code:
 *
 *   - level == 0  -> a GENERAL user, who consumes a seat from a license batch.
 *   - level  > 0  -> a BACKEND user (agent/admin/instructor), who needs no license,
 *                    and whose "batch id" is simply their level number.
 *
 * Seat allocation has two sources, tried in this order:
 *   1. the user ALREADY has a license row for (userId, packageId) -> reuse its batch
 *   2. otherwise the first batch of the company's agent that still has free capacity
 *      (licenseVolume > licenseUsed), earliest issueDate first
 *
 * and if neither yields a batch, put.php answers 405 and creates no user at all.
 */
@Service
public class LicenseAssignment {

    private final JdbcTemplate jdbc;

    public LicenseAssignment(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * PHP: get_licenseBatch($conn, $qdata) — returns the batch id to consume, or ""
     * when the company's agent has no capacity left.
     *
     * For level != 0 the PHP returns the LEVEL ITSELF (not a batch id). The caller
     * only tests it for truthiness, so returning the level as a string is faithful.
     */
    public String batchFor(JsonNode qdata) {
        double level = number(qdata, "level");
        if (level != 0) {
            return text(qdata, "level");
        }

        String userId = text(qdata, "userId");
        String companyName = text(qdata, "companyName");
        String lic = "";

        if (!userId.isEmpty()) {
            List<Map<String, Object>> rows = PhpRows.query(jdbc,
                    "select * from license_view where userId = ? and packageId = ?",
                    userId, companyName);
            if (!rows.isEmpty()) {
                lic = string(PhpRows.get(rows.get(0), "batchId"));
            }
        }

        // PHP: if (!$lic) — so "" and "0" both fall through to the batch search.
        if (phpFalsy(lic)) {
            List<Map<String, Object>> rows = PhpRows.query(jdbc,
                    "select * from batchlicense_view v, company c "
                            + "where c.agentId = v.agentId "
                            + "and v.licenseVolume > v.licenseUsed "
                            + "and c.packageId = ? "
                            + "order by v.issueDate, v.availableTill limit 1",
                    companyName);
            if (!rows.isEmpty()) {
                lic = string(PhpRows.get(rows.get(0), "batchId"));
            }
        }
        return lic;
    }

    /**
     * PHP: update_license($conn, $qdata, $bId) — attaches the general user to a
     * license row, creating it when absent.
     *
     * Note what it does NOT do: nothing for level != 0, and it never touches
     * availableFrom/availableTill/agentId. A license created here therefore has a
     * NULL agentId, which is why the real schema must allow that.
     */
    public void attach(JsonNode qdata, String batchId) {
        attach(qdata, batchId, text(qdata, "userId"));
    }

    /**
     * @param userId the EFFECTIVE user id. put.php assigns {@code $qdata->userId = uniqid()}
     *               BEFORE calling update_license, so the freshly minted id is what gets
     *               written to the license row. Callers that minted the id locally must
     *               pass it here, or the license ends up attached to an empty user.
     */
    public void attach(JsonNode qdata, String batchId, String userId) {
        if (number(qdata, "level") != 0) {
            return;
        }

        String companyName = text(qdata, "companyName");
        String userName = text(qdata, "userName");

        // $pId is the company's DISPLAY name (company.companyName), not the package id.
        String pId = "";
        List<Map<String, Object>> companies = PhpRows.query(jdbc,
                "select * from company where packageId = ?", companyName);
        if (!companies.isEmpty()) {
            pId = string(PhpRows.get(companies.get(0), "companyName"));
        }

        // PHP: if ($pId) — an empty display name means "do nothing at all".
        if (phpFalsy(pId)) {
            return;
        }

        List<Map<String, Object>> existing = PhpRows.query(jdbc,
                "select * from license where userId = ? and packageId = ? and batchId = ?",
                userId, companyName, batchId);
        if (!existing.isEmpty()) {
            String licenseId = string(PhpRows.get(existing.get(0), "licenseId"));
            jdbc.update("update license set userName = ?, companyName = ? where licenseId = ?",
                    userName, pId, licenseId);
        } else {
            jdbc.update("insert into license "
                            + "(licenseId, batchId, packageId, companyName, userId, userName) "
                            + "values (?, ?, ?, ?, ?, ?)",
                    PhpUniqid.next(), batchId, companyName, pId, userId, userName);
        }
    }

    // ------------------------------------------------------------- helpers

    /** PHP truthiness for the values these functions produce: null, "", "0" are falsy. */
    public static boolean phpFalsy(String s) {
        return s == null || s.isEmpty() || "0".equals(s);
    }

    static String text(JsonNode node, String field) {
        if (node == null) return "";
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText();
    }

    static double number(JsonNode node, String field) {
        if (node == null) return 0;
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) return 0;
        if (v.isNumber()) return v.asDouble();
        String s = v.asText().trim();
        if (s.isEmpty()) return 0;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String string(Object v) {
        return v == null ? "" : v.toString();
    }
}
