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
import io.netty.buffer.Unpooled;
import org.traccar.BaseProtocolEncoder;
import org.traccar.Context;
import org.traccar.Protocol;
import org.traccar.helper.DataConverter;
import org.traccar.model.Command;

import java.nio.charset.StandardCharsets;

public class HuabaoProtocolEncoder extends BaseProtocolEncoder {

    private static final int DEVICE_TYPE_JC181 = 55;
    private static final int DEVICE_TYPE_JC371 = 56;

    public HuabaoProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    private ByteBuf encodeTransparent(ByteBuf id, String payload, int subtype) {
        ByteBuf data = Unpooled.buffer();
        data.writeByte(subtype);
        data.writeBytes(payload.getBytes(StandardCharsets.US_ASCII));
        return HuabaoProtocolDecoder.formatMessage(
                HuabaoProtocolDecoder.MSG_TRANSPARENT_DOWNLINK, id, false, data);
    }

    @Override
    protected Object encodeCommand(Command command) {
        ByteBuf id = Unpooled.wrappedBuffer(
                DataConverter.parseHex(getUniqueId(command.getDeviceId())));
        ByteBuf data = Unpooled.buffer();
        try {
            switch (command.getType()) {
                case Command.TYPE_CUSTOM:
                    String payload = command.getString(Command.KEY_DATA);
                    int deviceType = Context.getIdentityManager().lookupAttributeInteger(
                            command.getDeviceId(), "deviceType", 0, false, false);
                    boolean jimiOnlineCommand =
                            deviceType == DEVICE_TYPE_JC181 || deviceType == DEVICE_TYPE_JC371;
                    return encodeTransparent(id, payload, jimiOnlineCommand ? 0xF0 : 0x40);
                case Command.TYPE_ENGINE_STOP:
                case Command.TYPE_ENGINE_RESUME:
                    data.writeCharSequence(command.getType().equals(Command.TYPE_ENGINE_STOP) ? "#0;1" : "#0;0",
                                StandardCharsets.US_ASCII);
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_TERMINAL_CONTROL, id, false, data);
                    // return encodeTransparent(id, "AS01BLO0\r\n");
                default:
                    return null;
            }
        } finally {
            id.release();
        }
    }

}
