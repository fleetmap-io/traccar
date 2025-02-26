/*
 * Copyright 2018 Anton Tananaev (anton@traccar.org)
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
package org.traccar.geocoder;

import javax.json.JsonObject;
import java.util.Random;

public class HereGeocoder extends JsonGeocoder {

    private static String formatUrl(String url, String id, String key, String language) {
        if (url == null) {
            url = "https://reverse.geocoder.ls.hereapi.com/6.2/reversegeocode.json";
        }
        url += "?mode=retrieveAddresses&maxresults=1";
        url += "&at=%f,%f,0";
        url += "&app_id=" + id;
        url += "&app_code=" + key;
        String[] keys = key.split(",");
        url += "&apiKey=" + keys[new Random().nextInt(keys.length)];
        if (language != null) {
            url += "&lang=" + language;
        }
        return url;
    }

    public HereGeocoder(
            String url, String id, String key, String language, int cacheSize, AddressFormat addressFormat) {
        super(formatUrl(url, id, key, language), cacheSize, addressFormat);
    }

    @Override
    public Address parseAddress(JsonObject json) {
        JsonObject result = json
                .getJsonArray("items")
                .getJsonObject(0)
                .getJsonObject("address");

        if (result != null) {
            Address address = new Address();

            if (result.containsKey("label")) {
                address.setFormattedAddress(result.getString("label"));
            }

            if (result.containsKey("houseNumber")) {
                address.setHouse(result.getString("houseNumber"));
            }
            if (result.containsKey("street")) {
                address.setStreet(result.getString("street"));
            }
            if (result.containsKey("city")) {
                address.setSettlement(result.getString("city"));
            }
            if (result.containsKey("district")) {
                address.setDistrict(result.getString("district"));
            }
            if (result.containsKey("state")) {
                address.setState(result.getString("state"));
            }
            if (result.containsKey("countryName")) {
                address.setCountry(result.getString("countryName").toUpperCase());
            }
            if (result.containsKey("postalCode")) {
                address.setPostcode(result.getString("postalCode"));
            }

            return address;
        }

        return null;
    }

}
