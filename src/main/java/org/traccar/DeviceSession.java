/*
 * Copyright 2016 - 2018 Anton Tananaev (anton@traccar.org)
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
package org.traccar;

import java.util.HashMap;
import java.util.TimeZone;
import java.util.Map;

public class DeviceSession {

    private final long deviceId;
    private final String model = null;

    public DeviceSession(long deviceId) {
        this.deviceId = deviceId;
    }

    public long getDeviceId() {
        return deviceId;
    }

    private TimeZone timeZone;

    public void setTimeZone(TimeZone timeZone) {
        this.timeZone = timeZone;
    }

    public TimeZone getTimeZone() {
        return timeZone;
    }

    public String getModel() {
        return model;
    }

    public static final String KEY_TIMEZONE = "timezone";
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) locals.get(key);
    }
    private final Map<String, Object> locals = new HashMap<>();

    public boolean contains(String key) {
        return locals.containsKey(key);
    }

    public void set(String key, Object value) {
        if (value != null) {
            locals.put(key, value);
        } else {
            locals.remove(key);
        }
    }
}
