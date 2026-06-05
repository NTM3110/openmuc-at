package org.openmuc.framework.server.restws.sql;

import java.sql.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmuc.framework.server.restws.domain.model.LatestValue;

import java.util.ArrayList;
import java.util.List;

public class LatestValueRepoImpl {
    private final static String url = "jdbc:postgresql://localhost:5432/openmuc";
    private final static String user = "openmuc_user";
    private final static String password = "openmuc";
    private static final String SELECT_PREFIX_SQL = "SELECT channelid, value_type, value_double, value_string, value_boolean, updated_at "
            +
            "FROM latest_values " +
            "WHERE channelid LIKE ?";

    private static final Logger logger = LoggerFactory.getLogger(LatestValueRepoImpl.class);

    public static List<LatestValue> findByChannelIdStartingWith(String prefix) {
        List<LatestValue> latestValues = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
                PreparedStatement ps = conn.prepareStatement(SELECT_PREFIX_SQL)) {

            // logger.info("Connected to DB: Finding latest values with channelId starting with {}", prefix);

            ps.setString(1, prefix + "%");

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    LatestValue lv = new LatestValue();
                    lv.setChannelId(rs.getString("channelid"));
                    lv.setValueType(rs.getString("value_type"));
                    lv.setValueDouble(rs.getDouble("value_double"));
                    lv.setValueString(rs.getString("value_string"));
                    lv.setValueBoolean(rs.getBoolean("value_boolean"));
                    lv.setUpdatedDatetime(rs.getString("updated_at"));
                    latestValues.add(lv);
                }
            }
        } catch (SQLException e) {
            logger.warn("[latest_values] findByChannelIdStartingWith failed for prefix " + prefix + ": " + e);
        }
        return latestValues;
    }

    public static LatestValue findLatestValueByChannelId(String channelId) {
        LatestValue lv = null;
        String sql = SELECT_PREFIX_SQL.replace("LIKE ?", "= ?");
        try (Connection conn = DriverManager.getConnection(url, user, password);
                PreparedStatement ps = conn.prepareStatement(sql)) {

            // logger.info("Connected to DB: Finding latest value for channelId {}", channelId);

            ps.setString(1, channelId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    lv = new LatestValue();
                    lv.setChannelId(rs.getString("channelid"));
                    lv.setValueType(rs.getString("value_type"));
                    lv.setValueDouble(rs.getDouble("value_double"));
                    lv.setValueString(rs.getString("value_string"));
                    lv.setValueBoolean(rs.getBoolean("value_boolean"));
                    lv.setUpdatedDatetime(rs.getString("updated_at"));
                }
            }
        } catch (SQLException e) {
            logger.warn("[latest_values] findLatestValueByChannelId failed for channelId " + channelId + ": " + e);
        }
        return lv;
    }

    public static void deleteAllByChannelIdStartingWith(String prefix) {
        String sql = "DELETE FROM latest_values WHERE channelid LIKE ?";
        try (Connection conn = DriverManager.getConnection(url, user, password);
                PreparedStatement ps = conn.prepareStatement(sql)) {

            // logger.info("Connected to DB: Deleting latest values with channelId starting with {}", prefix);

            ps.setString(1, prefix + "%");

            ps.executeUpdate();
        } catch (SQLException e) {
            logger.warn("[latest_values] deleteAllByChannelIdStartingWith failed for prefix " + prefix + ": " + e);
        }
    }
}