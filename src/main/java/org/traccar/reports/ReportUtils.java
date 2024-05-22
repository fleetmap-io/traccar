/*
 * Copyright 2016 - 2018 Anton Tananaev (anton@traccar.org)
 * Copyright 2016 - 2017 Andrey Kunitsyn (andrey@traccar.org)
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
package org.traccar.reports;

import org.apache.velocity.tools.generic.DateTool;
import org.apache.velocity.tools.generic.NumberTool;
import org.jxls.area.Area;
import org.jxls.builder.xls.XlsCommentAreaBuilder;
import org.jxls.common.CellRef;
import org.jxls.formula.StandardFormulaProcessor;
import org.jxls.transform.Transformer;
import org.jxls.transform.poi.PoiTransformer;
import org.jxls.util.TransformerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.Context;
import org.traccar.database.DeviceManager;
import org.traccar.database.IdentityManager;
import org.traccar.handler.events.MotionEventHandler;
import org.traccar.helper.DistanceCalculator;
import org.traccar.model.DeviceState;
import org.traccar.model.Driver;
import org.traccar.model.Event;
import org.traccar.model.Position;
import org.traccar.reports.model.BaseReport;
import org.traccar.reports.model.StopReport;
import org.traccar.reports.model.TripReport;
import org.traccar.reports.model.TripsConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class ReportUtils {

    private static double speedThreshold = Context.getConfig().getDouble("event.motion.speedThreshold", 0.01);

    private ReportUtils() {
    }

    public static void checkPeriodLimit(Date from, Date to) {
        long limit = Context.getConfig().getLong("report.periodLimit") * 1000;
        if (limit > 0 && to.getTime() - from.getTime() > limit) {
            throw new IllegalArgumentException("Time period exceeds the limit");
        }
    }

    public static String getDistanceUnit(long userId) {
        return (String) Context.getPermissionsManager().lookupAttribute(userId, "distanceUnit", "km");
    }

    public static String getSpeedUnit(long userId) {
        return (String) Context.getPermissionsManager().lookupAttribute(userId, "speedUnit", "kn");
    }

    public static String getVolumeUnit(long userId) {
        return (String) Context.getPermissionsManager().lookupAttribute(userId, "volumeUnit", "ltr");
    }

    public static TimeZone getTimezone(long userId) {
        String timezone = (String) Context.getPermissionsManager().lookupAttribute(userId, "timezone", null);
        return timezone != null ? TimeZone.getTimeZone(timezone) : TimeZone.getDefault();
    }

    public static Collection<Long> getDeviceList(Collection<Long> deviceIds, Collection<Long> groupIds) {
        Collection<Long> result = new ArrayList<>();
        result.addAll(deviceIds);
        for (long groupId : groupIds) {
            result.addAll(Context.getPermissionsManager().getGroupDevices(groupId));
        }
        return result;
    }

    public static double calculateDistance(
            Position firstPosition,
            Position lastPosition,
            Collection<Position> positions) {
        return calculateDistance(firstPosition, lastPosition, true, positions);
    }

    private static double calculateDistance(Position firstPosition, Position lastPosition, boolean useOdometer) {
        double distance = 0.0;
        double firstOdometer = firstPosition.getDouble(Position.KEY_ODOMETER);
        double lastOdometer = lastPosition.getDouble(Position.KEY_ODOMETER);

        if (useOdometer && (firstOdometer != 0.0 || lastOdometer != 0.0)) {
            distance = lastOdometer - firstOdometer;
        } else if (firstPosition.getAttributes().containsKey(Position.KEY_TOTAL_DISTANCE)
                && lastPosition.getAttributes().containsKey(Position.KEY_TOTAL_DISTANCE)) {
            distance = lastPosition.getDouble(Position.KEY_TOTAL_DISTANCE)
                    - firstPosition.getDouble(Position.KEY_TOTAL_DISTANCE);
        }

        return distance;
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportUtils.class);

    private static boolean isValid(Position start, Position end, double distance) {
        if (distance < 0) {
            return false;
        }

        long t = end.getFixTime().getTime() - start.getFixTime().getTime();
        if (0 == t) {
            // can data can vary 100 meters in the same second
            return (100 >= distance);
        }

        double kms = distance / 1000.0;
        double hours = t / 3600000.0;
        double averageSpeed = kms / hours;

        // can data can vary 200 meters in one second
        int maxSpeed = start.getAttributes().containsKey(Position.KEY_ODOMETER) && t < 5000 ?
                1000 : (xpertPosition(start) ? 100 : 200);

        LOGGER.error("avgSpeed: " + averageSpeed + " " + maxSpeed + " " +  start.getDeviceId() + " " + start.getFixTime());
        return averageSpeed < maxSpeed;
    }

    private static boolean xpertPosition(Position position) {
        return position.getAttributes().containsKey(Position.KEY_ODOMETER) && position.getProtocol() == "osmand";
    }

    public static double calculateDistance(
            Position firstPosition,
            Position lastPosition,
            boolean useOdometer,
            Collection<Position> positions) {

        double distance = calculateDistance(firstPosition, lastPosition, useOdometer);
        if (null == positions) {
            return distance;
        }
        if (isValid(firstPosition, lastPosition, distance)
                && firstPosition.getAttributes().containsKey(Position.KEY_ODOMETER)) {
            return distance;
        }

        //invalid distance - need to calculate a fixed one checking position by position
        Double fixedDistance = 0.0;
        Position previous = null;
        for (Position p : positions) {
            if (p.getFixTime().before(firstPosition.getFixTime())
                    || p.getFixTime().after(lastPosition.getFixTime())) {
                continue;
            }

            if (previous != null) {
                double positionDistance = calculateDistance(previous, p, useOdometer);
                if (isValid(previous, p, positionDistance)) {
                    fixedDistance += positionDistance;
                }
            }
            previous = p;
        }
        return fixedDistance;
    }

    public static double calculateFuel(Position firstPosition, Position lastPosition) {
        if (firstPosition.getAttributes().get(Position.KEY_FUEL_USED) != null
                && lastPosition.getAttributes().get(Position.KEY_FUEL_USED) != null) {
            BigDecimal value = new BigDecimal(lastPosition.getDouble(Position.KEY_FUEL_USED)
                    - firstPosition.getDouble(Position.KEY_FUEL_USED));
            return value.setScale(1, RoundingMode.HALF_EVEN).doubleValue();
        }
        if (firstPosition.getAttributes().get(Position.KEY_FUEL_LEVEL) != null
                && lastPosition.getAttributes().get(Position.KEY_FUEL_LEVEL) != null) {

            BigDecimal value = new BigDecimal(firstPosition.getDouble(Position.KEY_FUEL_LEVEL)
                    - lastPosition.getDouble(Position.KEY_FUEL_LEVEL));
            return value.setScale(1, RoundingMode.HALF_EVEN).doubleValue();
        }
        return 0;
    }

    public static String findDriver(Position firstPosition, Position lastPosition) {
        if (firstPosition.getAttributes().containsKey(Position.KEY_DRIVER_UNIQUE_ID)
                && !firstPosition.getString(Position.KEY_DRIVER_UNIQUE_ID).isEmpty()) {
            return firstPosition.getString(Position.KEY_DRIVER_UNIQUE_ID);
        } else if (lastPosition.getAttributes().containsKey(Position.KEY_DRIVER_UNIQUE_ID)) {
            return lastPosition.getString(Position.KEY_DRIVER_UNIQUE_ID);
        }
        return null;
    }

    public static String findDriverName(String driverUniqueId) {
        if (driverUniqueId != null && Context.getDriversManager() != null) {
            Driver driver = Context.getDriversManager().getDriverByUniqueId(driverUniqueId);
            if (driver != null) {
                return driver.getName();
            }
        }
        return null;
    }

    public static org.jxls.common.Context initializeContext(long userId) {
        org.jxls.common.Context jxlsContext = PoiTransformer.createInitialContext();
        jxlsContext.putVar("distanceUnit", getDistanceUnit(userId));
        jxlsContext.putVar("speedUnit", getSpeedUnit(userId));
        jxlsContext.putVar("volumeUnit", getVolumeUnit(userId));
        jxlsContext.putVar("webUrl", Context.getVelocityEngine().getProperty("web.url"));
        jxlsContext.putVar("dateTool", new DateTool());
        jxlsContext.putVar("numberTool", new NumberTool());
        jxlsContext.putVar("timezone", getTimezone(userId));
        jxlsContext.putVar("locale", Locale.getDefault());
        jxlsContext.putVar("bracketsRegex", "[\\{\\}\"]");
        return jxlsContext;
    }

    public static void processTemplateWithSheets(
            InputStream templateStream, OutputStream targetStream,
            org.jxls.common.Context jxlsContext) throws IOException {

        Transformer transformer = TransformerFactory.createTransformer(templateStream, targetStream);
        List<Area> xlsAreas = new XlsCommentAreaBuilder(transformer).build();
        for (Area xlsArea : xlsAreas) {
            xlsArea.applyAt(new CellRef(xlsArea.getStartCellRef().getCellName()), jxlsContext);
            xlsArea.setFormulaProcessor(new StandardFormulaProcessor());
            xlsArea.processFormulas();
        }
        transformer.deleteSheet(xlsAreas.get(0).getStartCellRef().getSheetName());
        transformer.write();
    }

    private static TripReport calculateTrip(
            ArrayList<Position> positions, int startIndex, int endIndex, boolean ignoreOdometer) {
        Position startTrip = positions.get(startIndex);
        Position endTrip = positions.get(endIndex);

        double speedMax = 0.0;
        double speedSum = 0.0;
        long idleTime = 0;
        Position last = startTrip;
        String driverUniqueId = null;
        for (int i = startIndex; i <= endIndex; i++) {
            Position position = positions.get(i);
            double speed = position.getSpeed();
            speedSum += speed;
            if (speed > speedMax) {
                speedMax = speed;
            }
            idleTime += getIdleTime(position, last);
            last = position;
            if (driverUniqueId == null
                    && position.getAttributes().containsKey(Position.KEY_DRIVER_UNIQUE_ID)
                    && !position.getString(Position.KEY_DRIVER_UNIQUE_ID).isEmpty()) {
                driverUniqueId = position.getString(Position.KEY_DRIVER_UNIQUE_ID);
            }
        }

        TripReport trip = new TripReport();

        long tripDuration = endTrip.getFixTime().getTime() - startTrip.getFixTime().getTime();
        long deviceId = startTrip.getDeviceId();
        trip.setDeviceId(deviceId);
        trip.setDeviceName(Context.getIdentityManager().getById(deviceId).getName());

        trip.setStartPositionId(startTrip.getId());
        trip.setStartLat(startTrip.getLatitude());
        trip.setStartLon(startTrip.getLongitude());
        trip.setStartTime(startTrip.getFixTime());
        String startAddress = startTrip.getAddress();
        if (startAddress == null && Context.getGeocoder() != null
                && Context.getConfig().getBoolean("geocoder.onRequest")) {
            startAddress = Context.getGeocoder().getAddress(startTrip.getLatitude(), startTrip.getLongitude(), null);
        }
        trip.setStartAddress(startAddress);

        trip.setEndPositionId(endTrip.getId());
        trip.setEndLat(endTrip.getLatitude());
        trip.setEndLon(endTrip.getLongitude());
        trip.setEndTime(endTrip.getFixTime());
        String endAddress = endTrip.getAddress();
        if (endAddress == null && Context.getGeocoder() != null
                && Context.getConfig().getBoolean("geocoder.onRequest")) {
            endAddress = Context.getGeocoder().getAddress(endTrip.getLatitude(), endTrip.getLongitude(), null);
        }
        trip.setEndAddress(endAddress);

        trip.setDistance(calculateDistance(startTrip, endTrip, !ignoreOdometer, positions));
        trip.setDuration(tripDuration);
        trip.setAverageSpeed(speedSum / (endIndex - startIndex));
        trip.setMaxSpeed(speedMax);
        trip.setIdleTime(idleTime);
        trip.setSpentFuel(calculateFuel(startTrip, endTrip));

        trip.setDriverUniqueId(driverUniqueId);
        trip.setDriverName(findDriverName(trip.getDriverUniqueId()));

        if (!ignoreOdometer
                && startTrip.getDouble(Position.KEY_ODOMETER) != 0
                && endTrip.getDouble(Position.KEY_ODOMETER) != 0) {
            trip.setStartOdometer(startTrip.getDouble(Position.KEY_ODOMETER));
            trip.setEndOdometer(endTrip.getDouble(Position.KEY_ODOMETER));
        } else {
            trip.setStartOdometer(startTrip.getDouble(Position.KEY_TOTAL_DISTANCE));
            trip.setEndOdometer(endTrip.getDouble(Position.KEY_TOTAL_DISTANCE));
        }

        return trip;
    }

    private static StopReport calculateStop(
            ArrayList<Position> positions, int startIndex, int endIndex, boolean ignoreOdometer) {

        Position startStop = positions.get(startIndex);
        Position endStop = positions.get(endIndex);

        StopReport stop = new StopReport();

        long deviceId = startStop.getDeviceId();
        stop.setDeviceId(deviceId);
        stop.setDeviceName(Context.getIdentityManager().getById(deviceId).getName());

        stop.setPositionId(startStop.getId());
        stop.setLatitude(startStop.getLatitude());
        stop.setLongitude(startStop.getLongitude());
        stop.setStartTime(startStop.getFixTime());
        String address = startStop.getAddress();
        if (address == null && Context.getGeocoder() != null
                && Context.getConfig().getBoolean("geocoder.onRequest")) {
            address = Context.getGeocoder().getAddress(stop.getLatitude(), stop.getLongitude(), null);
        }
        stop.setAddress(address);

        stop.setEndTime(endStop.getFixTime());

        long stopDuration = endStop.getFixTime().getTime() - startStop.getFixTime().getTime();
        stop.setDuration(stopDuration);
        stop.setSpentFuel(calculateFuel(startStop, endStop));

        long engineHours = 0;
        if (startStop.getAttributes().containsKey(Position.KEY_HOURS)
                && endStop.getAttributes().containsKey(Position.KEY_HOURS)) {
            engineHours = endStop.getLong(Position.KEY_HOURS) - startStop.getLong(Position.KEY_HOURS);
        } else if (Context.getConfig().getBoolean("processing.engineHours.enable")) {
            // Temporary fallback for old data, to be removed in May 2019
            for (int i = startIndex + 1; i <= endIndex; i++) {
                if (positions.get(i).getBoolean(Position.KEY_IGNITION)
                        && positions.get(i - 1).getBoolean(Position.KEY_IGNITION)) {
                    engineHours += positions.get(i).getFixTime().getTime()
                            - positions.get(i - 1).getFixTime().getTime();
                }
            }
        }
        stop.setEngineHours(engineHours);

        long idleTime = 0;
        Position last = startStop;
        for (int i = startIndex; i <= endIndex; i++) {
            Position position = positions.get(i);
            idleTime += getIdleTime(position, last);
            last = position;
        }
        stop.setIdleTime(idleTime);

        if (!ignoreOdometer
                && startStop.getDouble(Position.KEY_ODOMETER) != 0
                && endStop.getDouble(Position.KEY_ODOMETER) != 0) {
            stop.setStartOdometer(startStop.getDouble(Position.KEY_ODOMETER));
            stop.setEndOdometer(endStop.getDouble(Position.KEY_ODOMETER));
        } else {
            stop.setStartOdometer(startStop.getDouble(Position.KEY_TOTAL_DISTANCE));
            stop.setEndOdometer(endStop.getDouble(Position.KEY_TOTAL_DISTANCE));
        }

        return stop;

    }

    private static long getIdleTime(Position position, Position last) {
        if (position.getSpeed() < speedThreshold
                && last.getSpeed() < speedThreshold
                && position.getBoolean(Position.KEY_IGNITION)
                && last.getBoolean(Position.KEY_IGNITION)) {
            return position.getFixTime().getTime() - last.getFixTime().getTime();
        }
        return 0;
    }

    private static <T extends BaseReport> T calculateTripOrStop(
            ArrayList<Position> positions, int startIndex, int endIndex, boolean ignoreOdometer, Class<T> reportClass) {

        if (reportClass.equals(TripReport.class)) {
            return (T) calculateTrip(positions, startIndex, endIndex, ignoreOdometer);
        } else {
            return (T) calculateStop(positions, startIndex, endIndex, ignoreOdometer);
        }
    }

    private static boolean isMoving(ArrayList<Position> positions, int index, TripsConfig tripsConfig) {
        if (tripsConfig.getMinimalNoDataDuration() > 0) {
            boolean beforeGap = index < positions.size() - 1
                    && positions.get(index + 1).getFixTime().getTime() - positions.get(index).getFixTime().getTime()
                    >= tripsConfig.getMinimalNoDataDuration();
            boolean afterGap = index > 0
                    && positions.get(index).getFixTime().getTime() - positions.get(index - 1).getFixTime().getTime()
                    >= tripsConfig.getMinimalNoDataDuration();
            if (beforeGap || afterGap) {
                return false;
            }
        }
        if (!positions.get(index).getValid()) {
            return false;
        }
        if (positions.get(index).getAttributes().containsKey(Position.KEY_MOTION)
                && positions.get(index).getAttributes().get(Position.KEY_MOTION) instanceof Boolean) {
            return positions.get(index).getBoolean(Position.KEY_MOTION);
        } else {
            return positions.get(index).getSpeed() > tripsConfig.getSpeedThreshold();
        }
    }

    public static <T extends BaseReport> Collection<T> detectTripsAndStops(
            IdentityManager identityManager, DeviceManager deviceManager,
            Collection<Position> positionCollection,
            TripsConfig tripsConfig, boolean ignoreOdometer, Class<T> reportClass) {

        Collection<T> result = new ArrayList<>();

        ArrayList<Position> positions = new ArrayList<>(positionCollection);
        if (!positions.isEmpty()) {
            boolean trips = reportClass.equals(TripReport.class);
            MotionEventHandler  motionHandler = new MotionEventHandler(identityManager, deviceManager, tripsConfig);
            DeviceState deviceState = new DeviceState();
            deviceState.setMotionState(isMoving(positions, 0, tripsConfig));
            int startEventIndex = trips == deviceState.getMotionState() ? 0 : -1;
            int startNoEventIndex = -1;
            for (int i = 0; i < positions.size(); i++) {
                Map<Event, Position> event = motionHandler.updateMotionState(deviceState, positions.get(i),
                        isMoving(positions, i, tripsConfig));
                if (startEventIndex == -1
                        && (trips != deviceState.getMotionState() && deviceState.getMotionPosition() != null
                        || trips == deviceState.getMotionState() && event != null)) {
                    startEventIndex = i;
                    startNoEventIndex = -1;
                } else if (trips != deviceState.getMotionState() && startEventIndex != -1
                        && deviceState.getMotionPosition() == null && event == null) {
                    startEventIndex = -1;
                }
                if (startNoEventIndex == -1
                        && (trips == deviceState.getMotionState() && deviceState.getMotionPosition() != null
                        || trips != deviceState.getMotionState() && event != null)) {
                    startNoEventIndex = i;
                } else if (startNoEventIndex != -1 && deviceState.getMotionPosition() == null && event == null) {
                    startNoEventIndex = -1;
                }
                if (startEventIndex != -1 && startNoEventIndex != -1 && event != null
                        && trips != deviceState.getMotionState()) {
                    result.add(calculateTripOrStop(positions, startEventIndex, startNoEventIndex,
                            ignoreOdometer, reportClass));
                    startEventIndex = -1;
                }
            }
            if (startEventIndex != -1 && (startNoEventIndex != -1 || !trips)) {
                result.add(calculateTripOrStop(positions, startEventIndex,
                            startNoEventIndex != -1 ? startNoEventIndex : positions.size() - 1,
                            ignoreOdometer, reportClass));
            }
        }

        return result;
    }

}
