package org.traccar.handler;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.Test;
import org.traccar.database.IdentityManager;
import org.traccar.model.Device;
import org.traccar.model.Position;

public class LegalDrivingHandlerTest {

    private static class Manager implements IdentityManager {

        private final Device device = new Device();
        private Position last;

        @Override
        public long addUnknownDevice(String uniqueId) {
            return 1;
        }

        @Override
        public Device getById(long id) {
            return device;
        }

        @Override
        public Device getByUniqueId(String uniqueId) {
            return device;
        }

        @Override
        public String getDevicePassword(long id, String protocol, String defaultPassword) {
            return defaultPassword;
        }

        @Override
        public Position getLastPosition(long deviceId) {
            return last;
        }

        @Override
        public boolean isLatestPosition(Position position) {
            return true;
        }

        @Override
        public boolean lookupAttributeBoolean(
                long deviceId, String attributeName, boolean defaultValue, boolean lookupServer, boolean lookupConfig) {
            return defaultValue;
        }

        @Override
        public String lookupAttributeString(
                long deviceId, String attributeName, String defaultValue, boolean lookupServer, boolean lookupConfig) {
            return defaultValue;
        }

        @Override
        public int lookupAttributeInteger(
                long deviceId, String attributeName, int defaultValue, boolean lookupServer, boolean lookupConfig) {
            return defaultValue;
        }

        @Override
        public long lookupAttributeLong(
                long deviceId, String attributeName, long defaultValue, boolean lookupServer, boolean lookupConfig) {
            return defaultValue;
        }

        @Override
        public double lookupAttributeDouble(
                long deviceId, String attributeName, double defaultValue, boolean lookupServer, boolean lookupConfig) {
            return defaultValue;
        }

    }

    private Manager manager;
    private LegalDrivingHandler handler;
    private long time;
    private List<Integer> alarms;

    private void start(boolean enabled) {
        manager = new Manager();
        manager.device.set(LegalDrivingHandler.ATTRIBUTE_ENABLE, enabled);
        manager.device.set(LegalDrivingHandler.ATTRIBUTE_LEVEL2_TIME, 120);
        handler = new LegalDrivingHandler(manager);
        time = 0;
        alarms = new ArrayList<>();
    }

    private Position step(boolean driving, String alarm) {
        Position position = new Position();
        position.setDeviceId(1);
        position.setFixTime(new Date(time));
        position.set(Position.KEY_IGNITION, driving);
        position.set(Position.KEY_MOTION, driving);
        position.set(Position.KEY_ALARM, alarm);
        handler.handlePosition(position);
        if (position.getAttributes().containsKey(LegalDrivingHandler.KEY_LEVEL)) {
            alarms.add(position.getInteger(LegalDrivingHandler.KEY_LEVEL));
        }
        manager.last = position;
        time += 60 * 1000;
        return position;
    }

    private void drive(int minutes) {
        for (int i = 0; i < minutes; i++) {
            step(true, null);
        }
    }

    private void stop(int minutes) {
        for (int i = 0; i < minutes; i++) {
            step(false, null);
        }
    }

    private long drivingMinutes() {
        return manager.last.getLong(LegalDrivingHandler.KEY_DRIVING_TIME) / 60000;
    }

    @Test
    public void testBreakResets() {
        start(true);
        drive(111);
        stop(21);
        drive(111);
        assertEquals(List.of(), alarms);
    }

    @Test
    public void testLevel2() {
        start(true);
        drive(151);
        stop(16);
        assertEquals(List.of(2), alarms);
        assertEquals(0, drivingMinutes());
    }

    @Test
    public void testLevel1ShortBreakDoesNotReset() {
        start(true);
        drive(301);
        stop(21);
        drive(60);
        assertEquals(List.of(2, 1), alarms);
        assertEquals(359, drivingMinutes());
    }

    @Test
    public void testLevel1LongBreakResets() {
        start(true);
        drive(301);
        stop(46);
        assertEquals(0, drivingMinutes());
        drive(60);
        assertEquals(List.of(2, 1), alarms);
    }

    @Test
    public void testComputedAlarm() {
        start(true);
        assertNull(step(true, LegalDrivingHandler.ALARM_EXCESSIVE_DRIVING).getAttributes().get(Position.KEY_ALARM));
        start(false);
        assertEquals(LegalDrivingHandler.ALARM_EXCESSIVE_DRIVING,
                step(true, LegalDrivingHandler.ALARM_EXCESSIVE_DRIVING).getAttributes().get(Position.KEY_ALARM));
    }

}
