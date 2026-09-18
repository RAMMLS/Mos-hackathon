package ru.moshackathon.heatnetwork.model;

import org.locationtech.jts.geom.Geometry;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class InputFeature {
    private final String id;
    private final String objectType;
    private final Map<String, Object> properties;
    private final Geometry lonLatGeometry;
    private final Geometry metricGeometry;

    public InputFeature(String id, String objectType, Map<String, Object> properties,
                        Geometry lonLatGeometry, Geometry metricGeometry) {
        this.id = id;
        this.objectType = objectType;
        this.properties = Collections.unmodifiableMap(new LinkedHashMap<>(properties));
        this.lonLatGeometry = lonLatGeometry;
        this.metricGeometry = metricGeometry;
    }

    public String getId() {
        return id;
    }

    public String getObjectType() {
        return objectType;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }

    public Geometry getLonLatGeometry() {
        return lonLatGeometry;
    }

    public Geometry getMetricGeometry() {
        return metricGeometry;
    }

    public double getDouble(String key, double fallback) {
        Object value = properties.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof String) {
            try {
                return Double.parseDouble((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    public int getInt(String key, int fallback) {
        Object value = properties.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }
}
