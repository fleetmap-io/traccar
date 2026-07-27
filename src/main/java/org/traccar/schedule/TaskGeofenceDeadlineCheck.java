/*
 * Copyright 2020 Anton Tananaev (anton@traccar.org)
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
package org.traccar.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.Context;
import org.traccar.model.Event;
import org.traccar.model.Notification;
import org.traccar.model.Position;

import java.util.Arrays;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskGeofenceDeadlineCheck implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskGeofenceDeadlineCheck.class);

    private static final long CHECK_PERIOD_MINUTES = 15;

    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, CHECK_PERIOD_MINUTES, CHECK_PERIOD_MINUTES, TimeUnit.MINUTES);
    }

    @Override
    public void run() {
        for (long notificationId : Context.getNotificationManager().getAllItems()) {
            Notification notification = Context.getNotificationManager().getById(notificationId);
            if (notification != null && Event.TYPE_ALARM.equals(notification.getType())) {
                String alarmsAttribute = notification.getString("alarms");
                if (alarmsAttribute != null
                        && Arrays.asList(alarmsAttribute.split(",")).contains(Position.ALARM_GEOFENCE_ABSENCE)) {
                    LOGGER.error(
                            "Found geofence absence notification id={} name={} geofences={} timetable={}",
                            notificationId,
                            notification.getString("name"),
                            notification.getAttributes().get("geofences"),
                            notification.getAttributes().get("timetable"));
                }
            }
        }
    }

}