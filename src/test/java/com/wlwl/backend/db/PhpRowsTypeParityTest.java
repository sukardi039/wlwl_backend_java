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
        List<Map<String, Object>> rows = PhpRows.query(jdbc, "select * from company");
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);

        // decimal(6,2) -- PHP emits "1.50", NOT 1.5 or 1.50 as a JSON number
        assertThat(PhpRows.get(row, "creditBalance")).isEqualTo("1.50");

        // date -- "2026-01-15", never ISO-8601
        assertThat(PhpRows.get(row, "availableFrom")).isEqualTo("2026-01-15");

        // datetime -- space separator, no 'T', no fractional seconds
        assertThat(PhpRows.get(row, "lastSeen")).isEqualTo("2026-10-26 15:47:01");

        // int is an int (JSON number), because PHP's int columns come back as ints
        assertThat(PhpRows.get(row, "active")).isEqualTo(1);

        // varchar passes straight through
        assertThat(PhpRows.get(row, "commonPW")).isEqualTo("123456");
    }

    @Test
    @DisplayName("column order is preserved, matching select *")
    void columnOrderPreserved() {
        List<Map<String, Object>> rows = PhpRows.query(jdbc, "select * from company");
        assertThat(rows.get(0).keySet())
                .containsExactly("packageid", "companyname", "agentid", "active",
                        "availablefrom", "commonpw", "creditbalance", "lastseen");
    }
}
