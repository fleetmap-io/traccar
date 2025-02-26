/*
 * Copyright 2016 - 2018 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2018 Andrey Kunitsyn (andrey@traccar.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.handler.events;

import java.util.HashMap;
import java.util.Map;

import io.netty.channel.ChannelHandler;
import org.traccar.Context;
import org.traccar.database.IdentityManager;
import org.traccar.database.MaintenancesManager;
import org.traccar.model.Event;
import org.traccar.model.Maintenance;
import org.traccar.model.Position;

@ChannelHandler.Sharable
public class MaintenanceEventHandler extends BaseEventHandler {

    private final IdentityManager identityManager;
    private final MaintenancesManager maintenancesManager;

    public MaintenanceEventHandler(IdentityManager identityManager, MaintenancesManager maintenancesManager) {
        this.identityManager = identityManager;
        this.maintenancesManager = maintenancesManager;
    }

    @Override
    protected Map<Event, Position> analyzePosition(Position position) {
        if (identityManager.getById(position.getDeviceId()) == null
                || !identityManager.isLatestPosition(position)) {
            return null;
        }

        Position lastPosition = identityManager.getLastPosition(position.getDeviceId());
        if (lastPosition == null) {
            return null;
        }

        Map<Event, Position> events = new HashMap<>();
        for (long maintenanceId : maintenancesManager.getAllDeviceItems(position.getDeviceId())) {
            Maintenance maintenance = maintenancesManager.getById(maintenanceId);
            if (maintenance.getPeriod() != 0) {
                String type = maintenance.getType();
                if ("totalDistance".equals(type) && position.getDouble(Position.KEY_ODOMETER) != 0) {
                    boolean ignoreOdometer = Context.getDeviceManager()
                            .lookupAttributeBoolean(position.getDeviceId(), "report.ignoreOdometer",
                                    false, false, true);
                    if (!ignoreOdometer) {
                        type = "odometer";
                    }
                }
                double oldValue = lastPosition.getDouble(type);
                double newValue = position.getDouble(type);
                if (oldValue != 0.0 && newValue != 0.0 && newValue >= maintenance.getStart()) {
                    if (oldValue < maintenance.getStart()
                        || (long) ((oldValue - maintenance.getStart()) / maintenance.getPeriod())
                        < (long) ((newValue - maintenance.getStart()) / maintenance.getPeriod())) {
                        Event event = new Event(Event.TYPE_MAINTENANCE, position.getDeviceId(), position.getId());
                        event.setMaintenanceId(maintenanceId);
                        event.set(maintenance.getType(), newValue);
                        events.put(event, position);
                    }
                }
            }
        }

        return events;
    }

}
