/*
 * Copyright 2017 - 2020 Anton Tananaev (anton@traccar.org)
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
package org.traccar.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.traccar.BaseProtocolEncoder;
import org.traccar.Protocol;
import org.traccar.helper.DataConverter;
import org.traccar.model.Command;

public class HuabaoProtocolEncoder extends BaseProtocolEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(HuabaoProtocolEncoder.class);

    public HuabaoProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    @Override
    protected Object encodeCommand(Command command) {

        ByteBuf id = Unpooled.wrappedBuffer(
                DataConverter.parseHex(getUniqueId(command.getDeviceId())));
        try {
            ByteBuf data = Unpooled.buffer();
            ByteBuf result;
            switch (command.getType()) {
                case Command.TYPE_CUSTOM:
                    result = Unpooled.wrappedBuffer(DataConverter.parseHex(command.getString(Command.KEY_DATA)));
                    break;
                case Command.TYPE_ENGINE_STOP:
                    data.writeByte(0xf0);
                    result = HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_TERMINAL_CONTROL, id, false, data);
                    break;
                case Command.TYPE_ENGINE_RESUME:
                    data.writeByte(0xf1);
                    result = HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_TERMINAL_CONTROL, id, false, data);
                    break;
                default:
                    return null;
            }
            LOGGER.warn("[{}] command {}: {}",
                    command.getDeviceId(), command.getType(), ByteBufUtil.hexDump(result));
            return result;
        } finally {
            id.release();
        }
    }

}
