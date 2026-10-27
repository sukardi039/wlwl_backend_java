package com.wlwl.backend.db;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The single most important parity guarantee in the port: JSON value types must
 * match what PHP's json_encode produced. DECIMAL and DATE/DATETIME are the traps
 * — mysqli returns them as strings, so PHP emitted JSON strings, while Jackson
 * would emit numbers and ISO-8601.
 */
@SpringBootTest
@ActiveProfiles("test")
class PhpRowsTypeParityTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("decimal -> string, date/datetime -> PHP format, int stays int")
    void phpCompatibleTypes() {
        List<Map<String, Object>> rows =
                PhpRows.query(jdbc, "select * from company where packageId = 'pkg1'");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);

        // decimal(6,2) -- PHP emits "1.00", NOT 1.0 or 1 as a JSON number.
        // (company has no decimal column in production, so use taskefficiency.)
        Map<String, Object> te = PhpRows.query(jdbc,
                "select * from taskefficiency where productName = 'W1'").get(0);
        assertThat(PhpRows.get(te, "duration")).isEqualTo("1.00");

        // date -- "2026-01-15", never ISO-8601
        assertThat(PhpRows.get(row, "availableFrom")).isEqualTo("2026-01-15");

        // datetime -- space separator, no 'T', no fractional seconds
        Map<String, Object> drill = PhpRows.query(jdbc,
                "select * from drill where drillId = 'D1'").get(0);
        assertThat(PhpRows.get(drill, "startOn")).isEqualTo("2026-01-01 08:00:00");

        // int is an int (JSON number), because PHP's int columns come back as ints
        assertThat(PhpRows.get(row, "active")).isEqualTo(1);

        // varchar passes straight through
        assertThat(PhpRows.get(row, "commonPW")).isEqualTo("123456");
    }

    @Test
    @DisplayName("column order is preserved, matching select *")
    void columnOrderPreserved() {
        List<Map<String, Object>> rows =
                PhpRows.query(jdbc, "select * from company where packageId = 'pkg1'");
        assertThat(rows.get(0).keySet())
                .containsExactly("packageid", "companyname", "agentid", "active",
                        "runone", "activeone", "runtwo", "activetwo", "runthree",
                        "activethree", "availablefrom", "availabletill", "commonpw",
                        "lang", "companytype");
    }

    @Test
    @DisplayName("set() overwrites an existing column in place instead of adding a duplicate")
    void setOverwritesInPlace() {
        Map<String, Object> row = new java.util.LinkedHashMap<>();
        row.put("name", "Analytical");
        row.put("displaykey", "AN");

        PhpRows.set(row, "displayKey", "ZZ");

        // one key, not two; position preserved
        assertThat(row.keySet()).containsExactly("name", "displaykey");
        assertThat(PhpRows.get(row, "displayKey")).isEqualTo("ZZ");
    }
}
