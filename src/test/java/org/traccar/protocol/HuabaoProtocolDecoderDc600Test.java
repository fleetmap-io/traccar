package org.traccar.protocol;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.traccar.Context;
import org.traccar.ProtocolTest;
import org.traccar.TestIdentityManager;
import org.traccar.database.IdentityManager;
import org.traccar.model.Device;
import org.traccar.model.Position;

public class HuabaoProtocolDecoderDc600Test extends ProtocolTest {

    // TestIdentityManager is final, so delegate to it and only answer deviceType = 60
    private static final class Dc600IdentityManager implements IdentityManager {

        private final IdentityManager delegate = new TestIdentityManager();

        @Override
        public long addUnknownDevice(String uniqueId) {
            return delegate.addUnknownDevice(uniqueId);
        }

        @Override
        public Device getById(long id) {
            return delegate.getById(id);
        }

        @Override
        public Device getByUniqueId(String uniqueId) throws Exception {
            return delegate.getByUniqueId(uniqueId);
        }

        @Override
        public String getDevicePassword(long id, String protocol, String defaultPassword) {
            return delegate.getDevicePassword(id, protocol, defaultPassword);
        }

        @Override
        public Position getLastPosition(long deviceId) {
            return delegate.getLastPosition(deviceId);
        }

        @Override
        public boolean isLatestPosition(Position position) {
            return delegate.isLatestPosition(position);
        }

        @Override
        public boolean lookupAttributeBoolean(
                long deviceId, String attributeName, boolean defaultValue, boolean lookupServer, boolean lookupConfig) {
            return delegate.lookupAttributeBoolean(deviceId, attributeName, defaultValue, lookupServer, lookupConfig);
        }

        @Override
        public String lookupAttributeString(
                long deviceId, String attributeName, String defaultValue, boolean lookupServer, boolean lookupConfig) {
            return delegate.lookupAttributeString(deviceId, attributeName, defaultValue, lookupServer, lookupConfig);
        }

        @Override
        public int lookupAttributeInteger(
                long deviceId, String attributeName, int defaultValue, boolean lookupServer, boolean lookupConfig) {
            return "deviceType".equals(attributeName) ? 60 : defaultValue;
        }

        @Override
        public long lookupAttributeLong(
                long deviceId, String attributeName, long defaultValue, boolean lookupServer, boolean lookupConfig) {
            return delegate.lookupAttributeLong(deviceId, attributeName, defaultValue, lookupServer, lookupConfig);
        }

        @Override
        public double lookupAttributeDouble(
                long deviceId, String attributeName, double defaultValue, boolean lookupServer, boolean lookupConfig) {
            return delegate.lookupAttributeDouble(deviceId, attributeName, defaultValue, lookupServer, lookupConfig);
        }
    }

    @Before
    public void setUp() {
        Context.init(new Dc600IdentityManager(), Context.getMediaManager());
    }

    @After
    public void tearDown() {
        Context.init(new TestIdentityManager(), Context.getMediaManager());
    }

    @Test
    public void testDecode() throws Exception {

        HuabaoProtocolDecoder decoder = new HuabaoProtocolDecoder(null);

        // DC600 0x0200 whose additional items (0x01, 0x25, 0x30, 0x31) leave exactly
        // 20 bytes: 0x01 mileage is 0x1545 x 1/10 km = 544.5 km
        verifyAttribute(decoder, binary(
                "7e0200002e01234567890100b400000000000c000e01668ea102cc7b7c03ef0000000e261112083029010400001545250400000000300100310114fb7e"),
                Position.KEY_ODOMETER, 544500L);

    }

}
