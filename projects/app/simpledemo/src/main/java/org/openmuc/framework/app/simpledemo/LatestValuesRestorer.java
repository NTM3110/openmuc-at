package org.openmuc.framework.app.simpledemo;

import org.openmuc.framework.dataaccess.DataAccessService;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openmuc.framework.data.*;
import org.openmuc.framework.data.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class LatestValuesRestorer {

    private static final String url      = "jdbc:postgresql://localhost:5432/openmuc";
    private static final String user     = "openmuc_user";
    private static final String password = "openmuc";

    // @Reference
    // private DataAccessService dataAccessService;

    private static final Logger logger = LoggerFactory.getLogger(LatestValuesRestorer.class);

    // public LatestValuesRestorer(DataManager dm) {
    //     this.dataManager = dm;
    // }

    public static int restoreAll(DataAccessService dataAccessService, List<String> restoredChannelIds) {
        String sql = "SELECT channelid, value_type, value_double, value_string, value_boolean " +
                     "FROM latest_values";

        try (Connection conn = DriverManager.getConnection(url, user, password);
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            logger.info("CONNECTED TO LATEST_VALUES DB AND RESTORING VALUES");

            while (rs.next()) {
                String channelId  = rs.getString("channelid");
                String type       = rs.getString("value_type");
                Double d          = rs.getObject("value_double", Double.class);
                String s          = rs.getString("value_string");
                Boolean b         = (Boolean) rs.getObject("value_boolean");

                Value v;
                if ("D".equals(type) && d != null) {
                    v = new DoubleValue(d);
                }
                else if ("S".equals(type) && s != null) {
                    v = new StringValue(s);
                }
                else if ("B".equals(type) && b != null) {
                    v = new BooleanValue(b);
                }
                else {
                    continue; // invalid row, skip
                }
                if(!restoredChannelIds.contains(channelId)) continue;
                logger.info("Restoring channel {}: value: {}", channelId, v);
                long now = System.currentTimeMillis();
                Record r = new Record(v, now, Flag.VALID); // no time, just value
                dataAccessService.getChannel(channelId).setLatestRecord(r);
            }
            return 1;

        } catch (SQLException e) {
            logger.error("[latest_values] restoreAll failed: " + e);
            return 0;
        }
    }
}
