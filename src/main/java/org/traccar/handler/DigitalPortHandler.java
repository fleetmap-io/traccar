package org.traccar.handler;

import io.netty.channel.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseDataHandler;
import org.traccar.database.IdentityManager;
import org.traccar.model.Device;
import org.traccar.model.Position;

@ChannelHandler.Sharable
public class DigitalPortHandler extends BaseDataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDataHandler.class);

    private final IdentityManager identityManager;

    public DigitalPortHandler(IdentityManager identityManager) {
        this.identityManager = identityManager;
    }

    @Override
    protected Position handlePosition(Position position) {

        try {
            Position last = identityManager.getLastPosition(position.getDeviceId());
            Device device = identityManager.getById(position.getDeviceId());
            for(int i=1; i<=3; i++) {
                String sensor = "sensor" + i;
                String attribute = sensor + "Attribute";
                if (last != null && device.getAttributes().containsKey(sensor) && device.getAttributes().containsKey(attribute)) {
                    if (getProperty(last, attribute) != getProperty(position, attribute)) {
                        position.set(Position.KEY_ALARM, device.getAttributes().get(sensor).toString());
                    }
                }
            }
            // no dp || 0 -> 0 || 1 -> 0 - clear
            if (!getProperty(position, Position.KEY_DP2)) {
                return position;
            }

            //0 -> 1 - start counting
            if (null == last || !getProperty(last, Position.KEY_DP2)) {
                position.set(Position.KEY_DP2_TIME, 0);
            } else { //1 -> 1 - keep counting
                long dpTime = last.getLong(Position.KEY_DP2_TIME);
                long diff = position.getFixTime().getTime() - last.getFixTime().getTime();
                position.set(Position.KEY_DP2_TIME, dpTime + diff);
            }
        } catch (Exception ex) {
            LOGGER.warn("DigitalPortHandler failed, deviceId: {}, {}", position.getDeviceId(), ex.getMessage());
        }

        return position;
    }

    private boolean getProperty(Position p, String property) {
        if (!p.getAttributes().containsKey(property)) {
            return false;
        }

        Object o = p.getAttributes().get(property);
        if (o instanceof Boolean) {
            return (Boolean) o;
        } else if (o instanceof Number) {
            return ((Number) o).intValue() > 0;
        }
        return false;
    }
}
