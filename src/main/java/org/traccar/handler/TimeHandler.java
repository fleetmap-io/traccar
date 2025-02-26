/*
 * Copyright 2019 Anton Tananaev (anton@traccar.org)
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
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolDecoder;
import org.traccar.Context;
import org.traccar.config.Config;
import org.traccar.config.Keys;
import org.traccar.model.Position;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

@ChannelHandler.Sharable
public class TimeHandler extends ChannelInboundHandlerAdapter {

    private final boolean useServerTime;
    private final Set<String> protocols;
    private static final Logger LOGGER = LoggerFactory.getLogger(TimeHandler.class);

    public TimeHandler(Config config) {
        useServerTime = config.getString(Keys.TIME_OVERRIDE).equalsIgnoreCase("serverTime");
        String protocolList = Context.getConfig().getString(Keys.TIME_PROTOCOLS);
        if (protocolList != null) {
            protocols = new HashSet<>(Arrays.asList(protocolList.split("[, ]")));
        } else {
            protocols = null;
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof Position && (protocols == null
                || protocols.contains(ctx.pipeline().get(BaseProtocolDecoder.class).getProtocolName()))) {

            Position position = (Position) msg;
            if (position.getAttributes().containsKey("source")
                    && position.getAttributes().get("source").equals("import")) {
                LOGGER.warn("channelRead {} {} {}", this.getClass(), position.getDeviceId(), position.getFixTime());
            }

            if (useServerTime) {
                position.setDeviceTime(position.getServerTime());
            }
            position.setFixTime(position.getDeviceTime());

        }
        ctx.fireChannelRead(msg);
    }

}
