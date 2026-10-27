package org.traccar.handler;

import io.netty.channel.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseDataHandler;
import org.traccar.database.IdentityManager;
import org.traccar.model.Device;
import org.traccar.model.Position;

/**
 * Two-level continuous driving rule for the existing "Excessive Driving" alert (alarmEngineOn).
 * Only applies to devices with the attribute legalDriving = true; other devices keep the
 * tripTime based computed attribute.
 * A break (not moving, or ignition off) of at least legalDrivingBreak minutes resets the
 * driving counter. Once the counter is over legalDrivingLevel1Time, only a break of at least
 * legalDrivingLongBreak minutes resets it.
 * The alarm is raised once when the counter goes over minTripTime (Level 2, the alert's
 * max duration) and once when it goes over legalDrivingLevel1Time (Level 1).
 */
@ChannelHandler.Sharable
public class LegalDrivingHandler extends BaseDataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(LegalDrivingHandler.class);

    public static final String ATTRIBUTE_ENABLE = "legalDriving";
    public static final String ATTRIBUTE_LEVEL2_TIME = "minTripTime"; // minutes, set by the alert form
    public static final String ATTRIBUTE_LEVEL1_TIME = "legalDrivingLevel1Time"; // minutes
    public static final String ATTRIBUTE_BREAK = "legalDrivingBreak"; // minutes
    public static final String ATTRIBUTE_LONG_BREAK = "legalDrivingLongBreak"; // minutes

    public static final String KEY_DRIVING_TIME = "legalDrivingTime"; // ms
    public static final String KEY_BREAK_TIME = "legalBreakTime"; // ms
    public static final String KEY_LEVEL = "legalDrivingLevel";
    public static final String ALARM_EXCESSIVE_DRIVING = "alarmEngineOn";

    private static final long MAX_DRIVING_GAP = 5 * 60 * 1000;

    private final IdentityManager identityManager;

    public LegalDrivingHandler(IdentityManager identityManager) {
        this.identityManager = identityManager;
    }

    // device attributes can be numbers/booleans or strings, depending on where they were set
    private static long minutes(Device device, String key, long defaultValue) {
        Object value = device.getAttributes().get(key);
        double result = 0;
        if (value instanceof Number) {
            result = ((Number) value).doubleValue();
        } else if (value != null) {
            try {
                result = Double.parseDouble(value.toString());
            } catch (NumberFormatException e) {
                result = 0;
            }
        }
        return (long) ((result > 0 ? result : defaultValue) * 60 * 1000);
    }

    private static boolean isEnabled(Device device) {
        Object value = device.getAttributes().get(ATTRIBUTE_ENABLE);
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static boolean isDriving(Position position) {
        boolean ignition = !position.getAttributes().containsKey(Position.KEY_IGNITION)
                || position.getBoolean(Position.KEY_IGNITION);
        return ignition && position.getBoolean(Position.KEY_MOTION);
    }

    @Override
    protected Position handlePosition(Position position) {
        try {
            Device device = identityManager.getById(position.getDeviceId());
            if (device == null || !isEnabled(device)) {
                return position;
            }
            // the tripTime based computed attribute doesn't follow the break rules
            if (ALARM_EXCESSIVE_DRIVING.equals(position.getAttributes().get(Position.KEY_ALARM))) {
                position.getAttributes().remove(Position.KEY_ALARM);
            }
            Position last = identityManager.getLastPosition(position.getDeviceId());
            if (last == null) {
                return position;
            }

            long level2 = minutes(device, ATTRIBUTE_LEVEL2_TIME, 120);
            long level1 = minutes(device, ATTRIBUTE_LEVEL1_TIME, 270);
            long shortBreak = minutes(device, ATTRIBUTE_BREAK, 15);
            long longBreak = minutes(device, ATTRIBUTE_LONG_BREAK, 45);

            long lastDrivingTime = last.getLong(KEY_DRIVING_TIME);
            long drivingTime = lastDrivingTime;
            long breakTime = last.getLong(KEY_BREAK_TIME);
            long diff = position.getFixTime().getTime() - last.getFixTime().getTime();
            if (diff > 0) {
                boolean wasDriving = isDriving(last);
                boolean driving = isDriving(position);
                if (wasDriving && driving) {
                    if (diff < MAX_DRIVING_GAP) {
                        drivingTime += diff;
                    }
                    breakTime = 0;
                } else if (!wasDriving && !driving) {
                    breakTime += diff;
                } else {
                    breakTime = 0;
                }
                if (drivingTime > 0 && breakTime >= (drivingTime > level1 ? longBreak : shortBreak)) {
                    drivingTime = 0;
                }
            }
            position.set(KEY_DRIVING_TIME, drivingTime);
            position.set(KEY_BREAK_TIME, breakTime);

            Integer level = null;
            if (lastDrivingTime <= level1 && drivingTime > level1) {
                level = 1;
            } else if (lastDrivingTime <= level2 && drivingTime > level2) {
                level = 2;
            }
            if (level != null && !position.getAttributes().containsKey(Position.KEY_ALARM)) {
                position.set(Position.KEY_ALARM, ALARM_EXCESSIVE_DRIVING);
                position.set(KEY_LEVEL, level);
            }
        } catch (Exception ex) {
            LOGGER.warn("LegalDrivingHandler failed, deviceId: {}, {}", position.getDeviceId(), ex.getMessage());
        }
        return position;
    }

}
