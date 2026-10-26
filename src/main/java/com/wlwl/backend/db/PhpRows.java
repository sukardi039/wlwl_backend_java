package com.wlwl.backend.db;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns JDBC result sets into the exact JSON the PHP produced.
 *
 * This class exists because the wire format is PHP's, not Java's. mysqli hands
 * PHP strings for DECIMAL, DATE, DATETIME and TIME, and json_encode therefore
 * emits them as JSON strings. Jackson would emit BigDecimal and LocalDateTime as
 * numbers / ISO-8601, which silently breaks the Vue client.
 *
 * Conversion table (PHP mysqli_fetch_assoc + json_encode):
 *
 *   int / bigint            -> JSON number      -> Integer / Long
 *   decimal(6,2)            -> JSON string      -> "0.00"      (getString)
 *   date                    -> JSON string      -> "2026-10-26"
 *   datetime / timestamp    -> JSON string      -> "2026-10-26 15:47:01"
 *   time                    -> JSON string      -> "15:47:01"
 *   char / varchar / text   -> JSON string
 *   NULL                    -> JSON null
 *
 * Column order is preserved via LinkedHashMap, matching `select *`.
 */
public final class PhpRows {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private PhpRows() { }

    public static List<Map<String, Object>> query(JdbcTemplate jdbc, String sql, Object... args) {
        return jdbc.query(sql, ROW_MAPPER, args);
    }

    public static final RowMapper<Map<String, Object>> ROW_MAPPER = PhpRows::mapRow;

    private static Map<String, Object> mapRow(ResultSet rs, int rowNum) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int columnCount = md.getColumnCount();
        Map<String, Object> row = new LinkedHashMap<>(Math.max(16, columnCount * 2));
        for (int c = 1; c <= columnCount; c++) {
            // PHP's assoc fetch keys on the column name (or alias), never on index.
            String name = md.getColumnLabel(c);
            row.put(name, value(rs, c, md.getColumnType(c)));
        }
        return row;
    }

    private static Object value(ResultSet rs, int index, int sqlType) throws SQLException {
        Object raw = rs.getObject(index);
        if (raw == null || rs.wasNull()) return null;

        switch (sqlType) {
            case java.sql.Types.DECIMAL:
            case java.sql.Types.NUMERIC:
                // mysqli returns decimals as strings; keep the scale exactly as stored.
                BigDecimal dec = rs.getBigDecimal(index);
                return dec == null ? null : dec.toPlainString();
            case java.sql.Types.TINYINT:
            case java.sql.Types.SMALLINT:
            case java.sql.Types.INTEGER:
                return rs.getInt(index);
            case java.sql.Types.BIGINT:
                return rs.getLong(index);
            case java.sql.Types.DATE:
                java.sql.Date d = rs.getDate(index);
                return d == null ? null : d.toLocalDate().format(DATE);
            case java.sql.Types.TIME:
                java.sql.Time t = rs.getTime(index);
                return t == null ? null : t.toLocalTime().format(TIME);
            case java.sql.Types.TIMESTAMP:
                java.sql.Timestamp ts = rs.getTimestamp(index);
                return ts == null ? null : ts.toLocalDateTime().format(DATETIME);
            case java.sql.Types.BIT:
            case java.sql.Types.BOOLEAN:
                return rs.getInt(index) != 0;
            default:
                return rs.getString(index);
        }
    }

    /**
     * PHP's `$jsonarray[] = $row` else `$jsonarray = $row` idiom, used by ~52 files.
     *
     * When the request has NO query string the response is an ARRAY of rows; when
     * it HAS one it collapses to the LAST row as a single OBJECT. This is not a
     * bug to fix during the port — the Vue client depends on both shapes.
     */
    public static Object arrayOrLastRow(List<Map<String, Object>> rows, int queryStringLength) {
        if (rows == null || rows.isEmpty()) return new ArrayList<>();
        if (queryStringLength == 0) return rows;
        return rows.get(rows.size() - 1);
    }

    /**
     * Exact-case lookup first, then case-insensitive fallback.
     *
     * MySQL returns column labels with the case they were declared in, so exact
     * lookup is correct in production. This exists so the same module code also
     * works against H2 (which lowercases unquoted identifiers) in tests, instead
     * of silently producing nulls.
     */
    public static Object get(Map<String, Object> row, String column) {
        if (row.containsKey(column)) return row.get(column);
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (e.getKey().equalsIgnoreCase(column)) return e.getValue();
        }
        return null;
    }
}
