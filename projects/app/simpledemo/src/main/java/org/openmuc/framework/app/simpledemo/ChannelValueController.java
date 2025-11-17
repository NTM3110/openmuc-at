package org.openmuc.framework.app.simpledemo;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.openmuc.framework.data.BooleanValue;
import org.openmuc.framework.data.DoubleValue;
import org.openmuc.framework.data.Flag;
import org.openmuc.framework.data.Record;
import org.openmuc.framework.data.StringValue;
import org.openmuc.framework.data.Value;
import org.openmuc.framework.dataaccess.Channel;
import org.openmuc.framework.dataaccess.DataAccessService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ChannelValueController (OpenMUC / Java 8+)
 *
 * - Caches latest values per channel (Boolean / Integer / Double / String / null)
 * - On listener event: if value changed -> persist to Postgres + setLatestRecord()
 * - Suppresses exactly one echo caused by setLatestRecord() to avoid listener loops
 *
 * Usage:
 *   ChannelValueController ctrl = new ChannelValueController(dataAccessService);
 *   ctrl.syncChannelSet(latestSaveChannelNames);       // whenever channel list changes
 *   ctrl.seedFromChannels(latestSaveChannelNames);     // (optional) after DB restore
 *   // inside your RecordListener:
 *   ctrl.processListenerEvent(channelId, record);
 */
public class ChannelValueController {
    private static final Logger log = LoggerFactory.getLogger(ChannelValueController.class);
    private static final double EPS = 1e-9;

    private final DataAccessService das;

    // cache: channelId -> latest normalized value
    private final Map<String, Object> cache = new ConcurrentHashMap<String, Object>();
    // one-shot echo suppression: channelId -> value we just wrote back
    private final Map<String, Object> suppressNextCallback = new ConcurrentHashMap<String, Object>();

    public ChannelValueController(DataAccessService das) {
        this.das = das;
    }

    /* ====================== Lifecycle helpers ====================== */

    /** Keep cache keys aligned with current channels (preserve any existing values). */
  /** Keep cache keys aligned with current channels (preserve any existing values). */
    public void syncChannelSet(Collection<String> channelIds) {
        log.info("Syncing cache with {} channels", (channelIds == null) ? 0 : channelIds.size());
        if (channelIds == null) return;

        // Keep only keys we still need
        java.util.HashSet<String> wanted = new java.util.HashSet<String>(channelIds);
        cache.keySet().retainAll(wanted);
        suppressNextCallback.keySet().retainAll(wanted);

        // Add new keys only when we have an existing non-null cached value
        for (String id : channelIds) {
            Object value = cache.get(id);
            if (value != null) {
                cache.put(id, value);
            }
        }
  // If you *want* to explicitly mark missing ones, use a placeholder instead of null:    // cache.put(id, UNSET);  // where UNSET = new Object();
    }


    /** Seed cache by reading channels’ current latest records (use after DB restore). */
    public void seedFromChannels(Collection<String> channelIds) {
        for (String id : channelIds) {
            Channel ch = das.getChannel(id);
            if (ch == null) continue;
            Record rec = ch.getLatestRecord();
            if (rec == null || rec.getValue() == null) continue;
            Class<?> t = expectedType(id);
            Object raw = readForType(t, rec);
            try {
                cache.put(id, normalizeTo(t, raw));
            } catch (Exception ignored) { /* leave null if bad */ }
        }
    }

    /* ==================== Main entry from listener ==================== */

    /**
     * Call this inside your RecordListener.
     * - Only writes DB + setLatestRecord when value differs from cache.
     * - Next callback caused by setLatestRecord is ignored exactly once (no loop).
     */
    public void processListenerEvent(String channelId, Record record) {
        if (record == null || record.getValue() == null) return;

        Class<?> t = expectedType(channelId);

        // 1) read & normalize incoming value
        final Object normalized;
        try {
            Object raw = readForType(t, record);
            normalized = normalizeTo(t, raw);
        } catch (Exception e) {
            log.warn("Normalize failed for {}: {}", channelId, e.getMessage());
            return;
        }

        // 2) ignore our own echo once
        Object mark = suppressNextCallback.get(channelId);
        if (mark != null && !different(mark, normalized)) {
            suppressNextCallback.remove(channelId);
            cache.put(channelId, normalized);
            log.debug("Suppressed echo for {} -> {}", channelId, normalized);
            return;
        }

        // 3) compare vs cache; if unchanged, do nothing
        Object old = cache.get(channelId);
        if (!different(old, normalized)) return;

        // 4) persist to Postgres (using your LatestValuesDao)
        try {
            if (t == Boolean.class) {
                LatestValuesDao.updateBoolean(channelId, ((Boolean) normalized).booleanValue());
            } else if (t == Integer.class) {
                LatestValuesDao.updateDouble(channelId, ((Integer) normalized).doubleValue());
            } else if (t == Double.class) {
                LatestValuesDao.updateDouble(channelId, ((Double) normalized).doubleValue());
            } else {
                LatestValuesDao.updateString(channelId, (String) normalized);
            }
        } catch (Exception e) {
            log.error("DB update failed for {}: {}", channelId, e.getMessage());
            return;
        }

        // 5) update cache
        cache.put(channelId, normalized);

        // 6) write back so HTTP GET sees it; suppress the next echo
        Channel ch = das.getChannel(channelId);
        if (ch != null) {
            try {
                suppressNextCallback.put(channelId, normalized);
                Record newRec = buildRecordFrom(normalized);
                ch.setLatestRecord(newRec);
            } catch (Exception e) {
                suppressNextCallback.remove(channelId);
                log.error("setLatestRecord failed for {}: {}", channelId, e.getMessage());
            }
        }

        log.info("Listener updated {} -> {}", channelId, normalized);
    }

    /* ===================== Type rules & helpers ===================== */

    /** Expected Java type based on your channel naming. Adjust if needed. */
    private static Class<?> expectedType(String id) {
        if ("soh_process_status".equals(id)) return Boolean.class;
        if ("dev_serial_comm_number".equals(id)) return Integer.class;
        if (id.startsWith("str")) {
            if (id.endsWith("_cell_qty"))  return Integer.class;
            if (id.endsWith("_Cnominal"))  return Double.class;
            if (id.endsWith("_Vnominal"))  return Double.class;
            // string_name / cell_brand / cell_model -> String
        }
        return String.class;
    }

    /** Read via the correct getter for the expected type. */
    private static Object readForType(Class<?> t, Record rec) {
        if (rec == null || rec.getValue() == null) return null;
        Value v = rec.getValue();
        if (t == Boolean.class) return v.asBoolean();
        if (t == Integer.class) {
            // OpenMUC has no asInt(); convert double->int for numeric integer channels
            Double d = v.asDouble();
            return (d == null) ? null : Integer.valueOf(d.intValue());
        }
        if (t == Double.class)  return v.asDouble();
        return v.asString();
    }

    /** Normalize any incoming to Boolean / Integer / Double / String. */
    private static Object normalizeTo(Class<?> type, Object raw) {
        if (raw == null) return null;

        if (type == Boolean.class) {
            if (raw instanceof Boolean) return ((Boolean) raw);
            if (raw instanceof String)  return Boolean.valueOf(((String) raw).trim());
            throw new IllegalArgumentException("Expected Boolean, got " + raw.getClass().getSimpleName());
        }

        if (type == Integer.class) {
            if (raw instanceof Integer) return ((Integer) raw);
            if (raw instanceof Number)  return Integer.valueOf(((Number) raw).intValue());
            if (raw instanceof String)  return Integer.valueOf(((String) raw).trim());
            throw new IllegalArgumentException("Expected Integer, got " + raw.getClass().getSimpleName());
        }

        if (type == Double.class) {
            if (raw instanceof Double)  return ((Double) raw);
            if (raw instanceof Number)  return Double.valueOf(((Number) raw).doubleValue());
            if (raw instanceof String)  return Double.valueOf(((String) raw).trim());
            throw new IllegalArgumentException("Expected Double, got " + raw.getClass().getSimpleName());
        }

        return String.valueOf(raw);
    }

    /** Equality with small tolerance for double noise (Java 8/11). */
    private static boolean different(Object a, Object b) {
        if (a == b) return false;
        if (a == null || b == null) return true;

        if (a instanceof Double && b instanceof Double) {
            double da = (Double) a;
            double db = (Double) b;
            if (Double.isNaN(da) && Double.isNaN(db)) return false;
            return Math.abs(da - db) > EPS;
        }
        return !a.equals(b);
    }

    /** Build a new OpenMUC Record with the normalized value and VALID flag. */
    private static Record buildRecordFrom(Object v) {
        if (v == null) {
            // represent unknown value
            return new Record(null, System.currentTimeMillis(), Flag.VALID);
        }

        final Value val;
        if (v instanceof Boolean) {
            val = new BooleanValue(((Boolean) v).booleanValue());
        } else if (v instanceof Integer) {
            // store ints as DoubleValue (numeric column)
            val = new DoubleValue(((Integer) v).doubleValue());
        } else if (v instanceof Double) {
            val = new DoubleValue(((Double) v).doubleValue());
        } else {
            val = new StringValue((String) v);
        }

        return new Record(val, System.currentTimeMillis(), Flag.VALID);
    }
}
