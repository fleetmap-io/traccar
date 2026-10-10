package org.traccar.handler;

import io.netty.channel.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseDataHandler;
import org.traccar.database.IdentityManager;
import org.traccar.model.Device;
import org.traccar.model.Position;

/**
 * Accumulates distance driven while the fuel counter (fuelUsed) does not increase,
 * or, for devices without a counter, while fuelConsumption is 0. Raises
 * alarmNoFuelConsumption once the distance reaches the device attribute
 * noFuelConsumptionDistance (km). Devices without that attribute are ignored.
 */
@ChannelHandler.Sharable
public class NoFuelConsumptionHandler extends BaseDataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(NoFuelConsumptionHandler.class);

    public static final String ATTRIBUTE_THRESHOLD = "noFuelConsumptionDistance"; // km, device attribute
    public static final String ALARM_NO_FUEL_CONSUMPTION = "alarmNoFuelConsumption";

    private final IdentityManager identityManager;

    public NoFuelConsumptionHandler(IdentityManager identityManager) {
        this.identityManager = identityManager;
    }

    @Override
    protected Position handlePosition(Position position) {
        try {
            Device device = identityManager.getById(position.getDeviceId());
            double threshold = device != null ? device.getDouble(ATTRIBUTE_THRESHOLD) * 1000 : 0;
            if (threshold <= 0) {
                return position;
            }
            Position last = identityManager.getLastPosition(position.getDeviceId());
            if (last == null) {
                return position;
            }

            Boolean consuming = null;
            if (position.getAttributes().containsKey(Position.KEY_FUEL_USED)
                    && last.getAttributes().containsKey(Position.KEY_FUEL_USED)) {
                consuming = position.getDouble(Position.KEY_FUEL_USED) > last.getDouble(Position.KEY_FUEL_USED);
            } else if (position.getAttributes().containsKey(Position.KEY_FUEL_CONSUMPTION)) {
                consuming = position.getDouble(Position.KEY_FUEL_CONSUMPTION) > 0;
            }
            if (consuming == null) {
                // no fuel data on this position: don't accumulate, so missing CAN data never raises the alarm
                return position;
            }

            double lastDistance = last.getDouble(Position.KEY_NO_FUEL_DISTANCE);
            double distance = 0;
            if (!consuming && position.getBoolean(Position.KEY_IGNITION)) {
                distance = lastDistance + position.getDouble(Position.KEY_DISTANCE);
            }
            position.set(Position.KEY_NO_FUEL_DISTANCE, distance);

            if (distance >= threshold && lastDistance < threshold
                    && !position.getAttributes().containsKey(Position.KEY_ALARM)) {
                position.set(Position.KEY_ALARM, ALARM_NO_FUEL_CONSUMPTION);
            }
        } catch (Exception ex) {
            LOGGER.warn("NoFuelConsumptionHandler failed, deviceId: {}, {}", position.getDeviceId(), ex.getMessage());
        }
        return position;
    }

}
