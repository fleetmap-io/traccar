/*
 * Copyright 2015 - 2019 Anton Tananaev (anton@traccar.org)
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
package org.traccar.handler;

import io.netty.channel.ChannelHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseDataHandler;
import org.traccar.database.DeviceManager;
import org.traccar.model.Position;

import java.util.concurrent.atomic.AtomicLong;

@ChannelHandler.Sharable
public class DefaultDataHandler extends BaseDataHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultDataHandler.class);

    AtomicLong id;

    public DefaultDataHandler(DeviceManager deviceManager) {

        long maxPositionId = 0;
        try {
            maxPositionId = deviceManager.getInitialState(1/*admin*/)
                                         .stream()
                                         .mapToLong(Position::getId)
                                         .max()
                                         .orElseGet(() -> 0);
        }
        catch (Exception ex){
            LOGGER.error("Failed to get maxPositionId. error - " + ex);
        }

        id = new AtomicLong(maxPositionId + 100000000);
        LOGGER.warn("maxPositionId: " + maxPositionId + " - position id starting with " + id.get());
    }

    @Override
    protected Position handlePosition(Position position) {

        if(position.getId() > 0){
            return position;
        }

        position.setId(id.getAndIncrement());

        return position;
    }
}
