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

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class TaskGeofenceDeadlineCheck implements Runnable {

    private static final Logger LOGGER = LoggerFactory.getLogger(TaskGeofenceDeadlineCheck.class);

    private static final long CHECK_PERIOD_MINUTES = 15;

    // TODO: timetable time zone should come from the user, hardcoded to Morocco for now
    private static final ZoneId TIMETABLE_ZONE = ZoneId.of("Africa/Casablanca");

    public void schedule(ScheduledExecutorService executor) {
        executor.scheduleAtFixedRate(this, CHECK_PERIOD_MINUTES, CHECK_PERIOD_MINUTES, TimeUnit.MINUTES);
    }

    @Override
    public void run() {
        long currentTime = System.currentTimeMillis();
        long checkPeriod = TimeUnit.MINUTES.toMillis(CHECK_PERIOD_MINUTES);

        for (long notificationId : Context.getNotificationManager().getAllItems()) {
            Notification notification = Context.getNotificationManager().getById(notificationId);
            if (notification != null && Event.TYPE_ALARM.equals(notification.getType())) {
                String alarmsAttribute = notification.getString("alarms");
                if (alarmsAttribute != null
                        && Arrays.asList(alarmsAttribute.split(",")).contains(Position.ALARM_GEOFENCE_ABSENCE)) {

                    Object timetableAttribute = notification.getAttributes().get("timetable");
                    if (!(timetableAttribute instanceof Map)) {
                        continue;
                    }

                    long deadline = getTodayDeadline((Map<?, ?>) timetableAttribute, currentTime);
                    if (deadline > 0 && currentTime >= deadline && currentTime - checkPeriod < deadline) {
                        LOGGER.info(
                                "Geofence absence deadline passed, notification id={} name={} geofences={}",
                                notificationId,
                                notification.getString("name"),
                                notification.getAttributes().get("geofences"));
                    }
                }
            }
        }
    }

    private long getTodayDeadline(Map<?, ?> timetable, long currentTime) {
        Object endTimeAttribute = timetable.get("endTime");
        if (!(endTimeAttribute instanceof String)) {
            return 0;
        }

        LocalTime endTime;
        try {
            endTime = LocalTime.parse((String) endTimeAttribute);
        } catch (DateTimeParseException error) {
            return 0;
        }

        Instant currentInstant = Instant.ofEpochMilli(currentTime);
        DayOfWeek today = currentInstant.atZone(TIMETABLE_ZONE).getDayOfWeek();

        boolean allWeek = Boolean.TRUE.equals(timetable.get("allWeek"));
        if (!allWeek) {
            Object weekDaysAttribute = timetable.get("weekDays");
            if (!(weekDaysAttribute instanceof Map)) {
                return 0;
            }
            String key = today.toString().toLowerCase(Locale.ROOT);
            if (!Boolean.TRUE.equals(((Map<?, ?>) weekDaysAttribute).get(key))) {
                return 0;
            }
        }

        return currentInstant.atZone(TIMETABLE_ZONE).toLocalDate().atTime(endTime)
                .atZone(TIMETABLE_ZONE).toInstant().toEpochMilli();
    }

}