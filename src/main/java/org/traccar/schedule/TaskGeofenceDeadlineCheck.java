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
import org.traccar.model.Geofence;
import org.traccar.model.Notification;
import org.traccar.model.Position;

import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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

        LOGGER.error("TaskGeofenceDeadlineCheck run, currentTime={}", Instant.ofEpochMilli(currentTime));

        for (long notificationId : Context.getNotificationManager().getAllItems()) {
            Notification notification = Context.getNotificationManager().getById(notificationId);
            if (notification != null && Event.TYPE_ALARM.equals(notification.getType())) {
                String alarmsAttribute = notification.getString("alarms");
                if (alarmsAttribute != null
                        && Arrays.asList(alarmsAttribute.split(",")).contains(Position.ALARM_GEOFENCE_ABSENCE)) {

                    Object timetableAttribute = notification.getAttributes().get("timetable");
                    if (!(timetableAttribute instanceof Map)) {
                        LOGGER.error(
                                "Geofence absence notification id={} name={} has no valid timetable",
                                notificationId, notification.getString("name"));
                        continue;
                    }
                    Map<?, ?> timetable = (Map<?, ?>) timetableAttribute;

                    long deadline = getTodayDeadline(timetable, currentTime);
                    boolean pastDeadline = deadline > 0 && currentTime >= deadline;
                    boolean withinWindow = pastDeadline && currentTime - checkPeriod < deadline;

                    if (withinWindow) {
                        long start = getTodayTime(timetable, "startTime", currentTime);
                        List<Long> geofenceIds = getGeofenceIds(notification);

                        LOGGER.error(
                                "Geofence absence deadline passed, notification id={} name={} start={} end={} "
                                        + "geofences={}",
                                notificationId,
                                notification.getString("name"),
                                start > 0 ? Instant.ofEpochMilli(start) : null,
                                Instant.ofEpochMilli(deadline),
                                geofenceIds);

                        if (start <= 0 || geofenceIds.isEmpty()) {
                            continue;
                        }

                        Date from = new Date(start);
                        Date to = new Date(deadline);
                        Set<Long> deviceIds;
                        if (notification.getAlways()) {
                            deviceIds = new HashSet<>();
                            for (long userId : Context.getNotificationManager().getItemUsers(notificationId)) {
                                deviceIds.addAll(Context.getDeviceManager().getAllUserItems(userId));
                            }
                        } else {
                            deviceIds = Context.getNotificationManager().getItemDevices(notificationId);
                        }

                        for (long deviceId : deviceIds) {
                            boolean visited = deviceVisitedGeofence(deviceId, geofenceIds, from, to);
                            if (!visited) {
                                LOGGER.error(
                                        "Geofence absence send alarm id={} deviceId={} visited=false",
                                        notificationId, deviceId);
                                // TODO: raise the alarm event
                            } else {
                                LOGGER.error(
                                        "Geofence OK id={} deviceId={} visited=false",
                                        notificationId, deviceId);
                            }
                        }
                    }
                }
            }
        }

        LOGGER.error("TaskGeofenceDeadlineCheck end, currentTime={}", Instant.ofEpochMilli(currentTime));
    }

    private boolean deviceVisitedGeofence(long deviceId, List<Long> geofenceIds, Date from, Date to) {
        try {
            for (Position position : Context.getDataManager().getPositions(deviceId, from, to)) {
                for (long geofenceId : geofenceIds) {
                    Geofence geofence = Context.getGeofenceManager().getById(geofenceId);
                    if (geofence != null && geofence.getGeometry()
                            .containsPoint(position.getLatitude(), position.getLongitude())) {
                        return true;
                    }
                }
            }
        } catch (SQLException error) {
            LOGGER.warn("Error checking geofence visits, deviceId " + deviceId, error);
        }
        return false;
    }

    private List<Long> getGeofenceIds(Notification notification) {
        List<Long> result = new ArrayList<>();
        Object geofencesAttribute = notification.getAttributes().get("geofences");
        if (geofencesAttribute instanceof List) {
            for (Object item : (List<?>) geofencesAttribute) {
                if (item instanceof Number) {
                    result.add(((Number) item).longValue());
                }
            }
        }
        return result;
    }

    private long getTodayDeadline(Map<?, ?> timetable, long currentTime) {
        if (!isActiveToday(timetable, currentTime)) {
            return 0;
        }
        return getTodayTime(timetable, "endTime", currentTime);
    }

    private boolean isActiveToday(Map<?, ?> timetable, long currentTime) {
        boolean allWeek = Boolean.TRUE.equals(timetable.get("allWeek"));
        if (allWeek) {
            return true;
        }

        Object weekDaysAttribute = timetable.get("weekDays");
        if (!(weekDaysAttribute instanceof Map)) {
            return false;
        }

        DayOfWeek today = Instant.ofEpochMilli(currentTime).atZone(TIMETABLE_ZONE).getDayOfWeek();
        String key = today.toString().toLowerCase(Locale.ROOT);
        return Boolean.TRUE.equals(((Map<?, ?>) weekDaysAttribute).get(key));
    }

    private long getTodayTime(Map<?, ?> timetable, String key, long currentTime) {
        Object timeAttribute = timetable.get(key);
        if (!(timeAttribute instanceof String)) {
            return 0;
        }

        LocalTime time;
        try {
            time = LocalTime.parse((String) timeAttribute);
        } catch (DateTimeParseException error) {
            return 0;
        }

        return Instant.ofEpochMilli(currentTime).atZone(TIMETABLE_ZONE).toLocalDate().atTime(time)
                .atZone(TIMETABLE_ZONE).toInstant().toEpochMilli();
    }

}