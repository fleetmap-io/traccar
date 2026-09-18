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
import java.util.HashMap;
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

        Map<Event, Position> events = new HashMap<>();

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

                        if (start <= 0 || geofenceIds.isEmpty()) {
                            continue;
                        }

                        List<Long> resolvedGeofenceIds = new ArrayList<>();
                        for (long geofenceId : geofenceIds) {
                            if (Context.getGeofenceManager().getById(geofenceId) != null) {
                                resolvedGeofenceIds.add(geofenceId);
                            } else {
                                // A geofence that hasn't loaded into GeofenceManager's cache yet (e.g. it was
                                // just created or the notification just had it attached) must not be treated
                                // as "not visited" - that reads identically to a genuine absence and fires a
                                // false alarm for every device on the notification at once.
                                LOGGER.error(
                                        "Skipping geofence absence check id={} geofenceId={}, geofence not found",
                                        notificationId, geofenceId);
                            }
                        }
                        if (resolvedGeofenceIds.isEmpty()) {
                            continue;
                        }
                        geofenceIds = resolvedGeofenceIds;

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
                            Set<Long> visitedGeofences =
                                    getVisitedGeofences(notificationId, deviceId, geofenceIds, from, to);
                            if (visitedGeofences == null) {
                                LOGGER.error(
                                        "Skipping geofence absence check id={} deviceId={}, "
                                        + "visit check failed instead of confirming absence",
                                        notificationId, deviceId);
                                continue;
                            }
                            for (long geofenceId : geofenceIds) {
                                if (!visitedGeofences.contains(geofenceId)) {
                                    LOGGER.error(
                                            "Geofence absence create event id={} deviceId={} geofenceId={}",
                                            notificationId, deviceId, geofenceId);
                                    Event event = new Event(Event.TYPE_ALARM, deviceId);
                                    event.set(Position.KEY_ALARM, Position.ALARM_GEOFENCE_ABSENCE);
                                    event.setGeofenceId(geofenceId);
                                    events.put(event, null);
                                } else {
                                    LOGGER.error(
                                            "Geofence visited id={} deviceId={} geofenceId={}",
                                            notificationId, deviceId, geofenceId);
                                }
                            }
                        }
                    }
                }
            }
        }

        if (!events.isEmpty()) {
            Context.getNotificationManager().updateEvents(events);
        }

    }

    /**
     * Returns the geofences visited by this device in [from, to], or {@code null} if the
     * check could not be completed or could not be trusted (e.g. a transient database error,
     * or no track data at all for the device in the window). Callers must treat a
     * {@code null} result as "unknown" and skip raising an absence alarm for it, rather than
     * treating it the same as an empty set - otherwise a failed or empty read looks identical
     * to a genuine absence and fires a false alarm.
     */
    private Set<Long> getVisitedGeofences(
            long notificationId, long deviceId, List<Long> geofenceIds, Date from, Date to) {
        Set<Long> visited = new HashSet<>();
        long queryStart = System.currentTimeMillis();
        int positionCount = 0;
        try {
            for (Position position : Context.getDataManager().getPositions(deviceId, from, to)) {
                positionCount++;
                for (long geofenceId : geofenceIds) {
                    if (visited.contains(geofenceId)) {
                        continue;
                    }
                    Geofence geofence = Context.getGeofenceManager().getById(geofenceId);
                    if (geofence != null && geofence.getGeometry()
                            .containsPoint(position.getLatitude(), position.getLongitude())) {
                        visited.add(geofenceId);
                    }
                }
            }
            // Logged unconditionally (not just on failure) so the next occurrence of a false
            // absence alarm can be diagnosed from the log alone: this line distinguishes "the
            // query returned nothing at all" (positionCount=0) from "it returned real positions
            // but none matched the geofence" (positionCount>0, visited=[]), which look identical
            // from the outside but point at completely different bugs.
            LOGGER.error(
                    "Geofence absence position check id={} deviceId={} from={} to={} "
                    + "positionCount={} visited={} queryMs={}",
                    notificationId, deviceId, from, to,
                    positionCount, visited, System.currentTimeMillis() - queryStart);
            if (positionCount == 0) {
                // No track data at all for this device in the whole window is not proof it
                // never entered the geofence - it may just as well mean the read failed to
                // return what is actually there (e.g. replica lag), which is indistinguishable
                // from a real gap without more signal. Treat it as unknown rather than absent.
                LOGGER.error(
                        "Skipping geofence absence check id={} deviceId={}, no position data in window",
                        notificationId, deviceId);
                return null;
            }
        } catch (SQLException error) {
            LOGGER.error("Error checking geofence visits, deviceId " + deviceId, error);
            return null;
        }
        return visited;
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