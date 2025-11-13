package org.openmuc.framework.app.bms;

import java.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class LatestValuesDao {
    private final static String url      = "jdbc:postgresql://127.0.0.1:5432/openmuc";
    private final static String user     = "openmuc_user";
    private final static String password = "openmuc";
    private static final Logger logger = LoggerFactory.getLogger(LatestValuesDao.class);

    private static final String UPSERT_SQL =
        "INSERT INTO latest_values (channelid, value_type, value_double, value_string, value_boolean, updated_at) " +
        "VALUES (?, ?, ?, ?, ?, now()) " +
        "ON CONFLICT (channelid) DO UPDATE " +
        "SET value_type    = EXCLUDED.value_type, " +
        "    value_double  = EXCLUDED.value_double, " +
        "    value_string  = EXCLUDED.value_string, " +
        "    value_boolean = EXCLUDED.value_boolean, " +
        "    updated_at    = EXCLUDED.updated_at";

    public static void updateDouble(String channelId, double value) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {

            logger.info("Connected to DB: Updating double value for channel {}: {}", channelId, value);

            ps.setString(1, channelId);   // channelid
            ps.setString(2, "D");         // value_type
            ps.setObject(3, value);       // value_double
            ps.setNull(4, Types.VARCHAR); // value_string
            ps.setNull(5, Types.BOOLEAN); // value_boolean

            ps.executeUpdate();
        }
        catch (SQLException e) {
            logger.warn("[latest_values] updateDouble failed for " + channelId + ": " + e);
        }
    }

    public static void updateString(String channelId, String value) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {

            logger.info("Connected to DB: Updating string value for channel {}: {}", channelId, value);

            ps.setString(1, channelId);
            ps.setString(2, "S");
            ps.setNull(3, Types.DOUBLE);  // value_double
            ps.setString(4, value);       // value_string
            ps.setNull(5, Types.BOOLEAN); // value_boolean

            ps.executeUpdate();
        }
        catch (SQLException e) {
            logger.warn("[latest_values] updateString failed for " + channelId + ": " + e);
        }
    }

    public static void updateBoolean(String channelId, boolean value) {
        try (Connection conn = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = conn.prepareStatement(UPSERT_SQL)) {

            logger.info("Connected to DB: Updating boolean value for channel {}: {}", channelId, value);

            ps.setString(1, channelId);
            ps.setString(2, "B");
            ps.setNull(3, Types.DOUBLE);    // value_double
            ps.setNull(4, Types.VARCHAR);   // value_string
            ps.setBoolean(5, value);        // value_boolean

            ps.executeUpdate();
        }
        catch (SQLException e) {
            logger.error("[latest_values] updateBoolean failed for " + channelId + ": " + e);
        }
    }
}
