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
import org.traccar.helper.DateUtil;
import org.traccar.model.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;

public class HuabaoProtocolEncoder extends BaseProtocolEncoder {

    private static final Logger LOGGER = LoggerFactory.getLogger(HuabaoProtocolEncoder.class);

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

    static ByteBuf encodeTerminalId(String uniqueId) {
        if (uniqueId.matches("[0-9]{15}")) {
            long imei = Long.parseLong(uniqueId.substring(0, 14));
            ByteBuf id = Unpooled.buffer(6);
            id.writeShort((int) (imei >> 32));
            id.writeInt((int) imei);
            return id;
        }
        return Unpooled.wrappedBuffer(DataConverter.parseHex(uniqueId));
    }

    private void writeDate(ByteBuf data, String value) {
        Date date = DateUtil.parseDate(value);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmmss");
        dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
        data.writeBytes(DataConverter.parseHex(dateFormat.format(date)));
    }

    @Override
    protected Object encodeCommand(Command command) {
        String uniqueId = getUniqueId(command.getDeviceId());
        LOGGER.error(
                "Huabao command encoding started deviceId={} uniqueId={} type={}",
                command.getDeviceId(), uniqueId, command.getType());
        ByteBuf id;
        try {
            id = encodeTerminalId(uniqueId);
        } catch (RuntimeException error) {
            LOGGER.error(
                    "Huabao command terminal ID encoding failed deviceId={} uniqueId={}",
                    command.getDeviceId(), uniqueId, error);
            throw error;
        }
        ByteBuf data = Unpooled.buffer();
        try {
            switch (command.getType()) {
                case Command.TYPE_CUSTOM:
                    String payload = command.getString(Command.KEY_DATA);
                    int deviceType = Context.getIdentityManager().lookupAttributeInteger(
                            command.getDeviceId(), "deviceType", 0, false, false);
                    boolean jimiOnlineCommand =
                            deviceType == DEVICE_TYPE_JC181 || deviceType == DEVICE_TYPE_JC371;
                    int subtype = jimiOnlineCommand ? 0xF0 : 0x40;
                    LOGGER.error(
                            "Huabao command encoded deviceId={} deviceType={} subtype=0x{} payload={}",
                            command.getDeviceId(), deviceType, Integer.toHexString(subtype).toUpperCase(),
                            payload.startsWith("APN,") ? "APN,<redacted>"
                                    : payload.replace("\r", "\\r").replace("\n", "\\n"));
                    return encodeTransparent(id, payload, subtype);
                case Command.TYPE_ENGINE_STOP:
                case Command.TYPE_ENGINE_RESUME:
                    data.writeCharSequence(command.getType().equals(Command.TYPE_ENGINE_STOP) ? "#0;1" : "#0;0",
                                StandardCharsets.US_ASCII);
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_TERMINAL_CONTROL, id, false, data);
                    // return encodeTransparent(id, "AS01BLO0\r\n");
                case Command.TYPE_VIDEO_START:
                    String server = command.getString(Command.KEY_SERVER);
                    int port = command.getInteger(Command.KEY_PORT);
                    int channel = command.getInteger(Command.KEY_INDEX);
                    data.writeByte(server.length());
                    data.writeCharSequence(server, StandardCharsets.US_ASCII);
                    data.writeShort(port); // TCP port
                    data.writeShort(0); // UDP port
                    data.writeByte(channel);
                    data.writeByte(1); // video only
                    data.writeByte(0); // main stream
                    LOGGER.error(
                            "Huabao video request encoded deviceId={} server={} port={} channel={}",
                            command.getDeviceId(), server, port, channel);
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_VIDEO_REQUEST, id, false, data);
                case Command.TYPE_VIDEO_STOP:
                    data.writeByte(command.getInteger(Command.KEY_INDEX));
                    data.writeByte(0); // close audio and video
                    data.writeByte(0); // both audio and video
                    data.writeByte(0); // main stream
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_VIDEO_CONTROL, id, false, data);
                case Command.TYPE_VIDEO_LIST:
                    data.writeByte(command.getInteger(Command.KEY_INDEX));
                    writeDate(data, command.getString(Command.KEY_START_TIME));
                    writeDate(data, command.getString(Command.KEY_END_TIME));
                    data.writeLong(0); // all alarm types
                    data.writeByte(2); // audio and video
                    data.writeByte(0); // all stream types
                    data.writeByte(0); // all storage types
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_VIDEO_LIST, id, false, data);
                case Command.TYPE_VIDEO_PLAYBACK:
                    server = command.getString(Command.KEY_SERVER);
                    port = command.getInteger(Command.KEY_PORT);
                    data.writeByte(server.length());
                    data.writeCharSequence(server, StandardCharsets.US_ASCII);
                    data.writeShort(port);
                    data.writeShort(0);
                    data.writeByte(command.getInteger(Command.KEY_INDEX));
                    data.writeByte(2); // audio and video
                    data.writeByte(0); // main or sub stream
                    data.writeByte(0); // device storage
                    data.writeByte(0); // normal playback
                    data.writeByte(0); // normal speed
                    writeDate(data, command.getString(Command.KEY_START_TIME));
                    writeDate(data, command.getString(Command.KEY_END_TIME));
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_VIDEO_PLAYBACK, id, false, data);
                default:
                    return null;
            }
        } finally {
            id.release();
        }
    }

}
