package org.openmuc.framework.lib.rest1.sql;

import java.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyManager;

import java.util.ArrayList;
import java.util.List;

public class EntityRepoImpl {
    private static final Logger logger = LoggerFactory.getLogger(EntityRepoImpl.class);

    private final static String url = "jdbc:postgresql://localhost:5432/openmuc";
    private final static String user = "openmuc_user";
    private final static String password = "openmuc";

    // private static final String GETTING_VALUES_SQL =
    // "SELECT v.\"VALUE\" AS value\n " +
    // "FROM ? v\n" +
    // "WHERE 1=1\n" +
    // "ORDER BY time DESC\n" +
    // "LIMIT 1";

    public Double getCurrentValue(String strId) {
        String table = strId + "_total_i";

        // very important: validate table to avoid SQL injection
        if (!table.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }

        String sql = "SELECT v.\"VALUE\" AS value\n" +
                "FROM " + table + " v\n" +
                "ORDER BY v.time DESC\n" +
                "LIMIT 1";

        try (Connection conn = DriverManager.getConnection(url, user, password);
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return rs.getDouble("value");
            }
        } catch (SQLException e) {
            logger.error("Error fetching current value for entity ID: {}", strId, e);
        }
        return 0D;
    }

    public Double getSocValue(String strId) {
        String table = strId + "_string_soc";

        // very important: validate table to avoid SQL injection
        if (!table.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }

        String sql = "SELECT v.\"VALUE\" AS value\n" +
                "FROM " + table + " v\n" +
                "ORDER BY v.time DESC\n" +
                "LIMIT 1";

        try (Connection conn = DriverManager.getConnection(url, user, password);
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return rs.getDouble("value") / 100.0;
            }
        } catch (SQLException e) {
            logger.error("Error fetching string SoC value for entity ID: {}", strId, e);
        }
        return 0D;
    }

    public Double getTemperatureValue(String strId) {
        String table = strId + "_ambient_t";

        // very important: validate table to avoid SQL injection
        if (!table.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Invalid table name: " + table);
        }

        String sql = "SELECT v.\"VALUE\" AS value\n" +
                "FROM " + table + " v\n" +
                "ORDER BY v.time DESC\n" +
                "LIMIT 1";

        try (Connection conn = DriverManager.getConnection(url, user, password);
                PreparedStatement ps = conn.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            if (rs.next()) {
                return rs.getDouble("value");
            }
        } catch (SQLException e) {
            logger.error("Error fetching ambient T value for entity ID: {}", strId, e);
        }
        return 0D;
    }
}
