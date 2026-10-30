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
    private static final int DEVICE_TYPE_JC450 = 57;

    public HuabaoProtocolEncoder(Protocol protocol) {
        super(protocol);
    }

    static ByteBuf encodeTransparent(ByteBuf id, String payload, int subtype) {
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

    // Cameras index SD-card recordings in their own local time, so the video
    // list / playback commands must send timestamps in that zone, matching
    // HuabaoProtocolDecoder's decode of the 0x1205 reply. Use the device
    // timezone (decoder.timezone), same as location reports, else UTC.
    private TimeZone videoTimeZone(long deviceId) {
        String name = Context.getIdentityManager().lookupAttributeString(
                deviceId, "decoder.timezone", null, false, true);
        return TimeZone.getTimeZone(name != null ? name : "UTC");
    }

    private static void writeDate(ByteBuf data, String value, TimeZone timeZone) {
        Date date = DateUtil.parseDate(value);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmmss");
        dateFormat.setTimeZone(timeZone);
        data.writeBytes(DataConverter.parseHex(dateFormat.format(date)));
    }

    static void encodeVideoListData(
            ByteBuf data, int channel, String startTime, String endTime, long alarmFlag, TimeZone timeZone) {
        data.writeByte(channel); // logical channel, 0 = all channels
        writeDate(data, startTime, timeZone);
        writeDate(data, endTime, timeZone);
        data.writeLong(alarmFlag); // 0 = all alarm types, otherwise a JT/T 1078 alarm bitmask
        data.writeByte(2); // audio and video
        data.writeByte(0); // all stream types
        data.writeByte(0); // all storage types
    }

    private static void writeString(ByteBuf data, String value) {
        byte[] bytes = (value != null ? value : "").getBytes(StandardCharsets.US_ASCII);
        data.writeByte(bytes.length);
        data.writeBytes(bytes);
    }

    // JT/T 1078 0x9206 file upload: the device uploads its SD-card recordings
    // for the time range to an FTP server, then reports 0x1206 when done. The
    // network condition is a bitmask of the links the upload may use
    // (bit 0 Wi-Fi, bit 1 LAN, bit 2 3G/4G); it defaults to Wi-Fi only, so a
    // bulk upload doesn't run over mobile data.
    static int encodeVideoUploadData(ByteBuf data, Command command, TimeZone timeZone) {
        int networkCondition = command.getAttributes().containsKey(Command.KEY_NETWORK_CONDITION)
                ? command.getInteger(Command.KEY_NETWORK_CONDITION) : 0x01;
        writeString(data, command.getString(Command.KEY_SERVER));
        data.writeShort(command.getInteger(Command.KEY_PORT));
        writeString(data, command.getString(Command.KEY_USERNAME));
        writeString(data, command.getString(Command.KEY_PASSWORD));
        writeString(data, command.getString(Command.KEY_PATH));
        data.writeByte(command.getInteger(Command.KEY_INDEX)); // logical channel, 0 = all channels
        writeDate(data, command.getString(Command.KEY_START_TIME), timeZone);
        writeDate(data, command.getString(Command.KEY_END_TIME), timeZone);
        data.writeLong(command.getLong(Command.KEY_ALARM_FLAG)); // 0 = all alarm types
        data.writeByte(command.getInteger(Command.KEY_RESOURCE_TYPE));
        data.writeByte(command.getInteger(Command.KEY_STREAM_TYPE));
        data.writeByte(command.getInteger(Command.KEY_STORAGE_TYPE));
        data.writeByte(networkCondition);
        return networkCondition;
    }

    static void encodeSetParameterData(ByteBuf data, int parameterId, byte[] value) {
        data.writeByte(1); // one parameter
        data.writeInt(parameterId);
        data.writeByte(value.length);
        data.writeBytes(value);
    }

    static int[] parseParameterIds(String value) {
        String[] parts = value.split("[ ,]+");
        int[] ids = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.startsWith("0x") || part.startsWith("0X")) {
                part = part.substring(2);
            }
            ids[i] = (int) Long.parseLong(part, 16);
        }
        return ids;
    }

    static void encodeQuerySpecificParameterData(ByteBuf data, int[] parameterIds) {
        data.writeByte(parameterIds.length);
        for (int parameterId : parameterIds) {
            data.writeInt(parameterId);
        }
    }

    static void encodeVideoRequestData(ByteBuf data, String server, int port, int channel) {
        data.writeByte(server.length());
        data.writeCharSequence(server, StandardCharsets.US_ASCII);
        data.writeShort(port); // TCP port
        data.writeShort(0); // UDP port
        data.writeByte(channel);
        data.writeByte(0); // audio and video
        data.writeByte(0); // main stream
    }

    // T/JSATL12 0x9208 alarm attachment upload request: attachment server address,
    // TCP/UDP ports, the 16-byte alarm identifier from the 0x64/0x65 alarm, a
    // 32-byte platform alarm number and 16 reserved bytes. The alarm number only
    // has to be unique; the hex identifier is exactly 32 characters.
    static void encodeAlarmAttachmentRequestData(ByteBuf data, String server, int port, String identifier) {
        data.writeByte(server.length());
        data.writeCharSequence(server, StandardCharsets.US_ASCII);
        data.writeShort(port); // TCP port
        data.writeShort(0); // UDP port
        data.writeBytes(ByteBufUtil.decodeHexDump(identifier));
        byte[] alarmNumber = new byte[32];
        byte[] value = identifier.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(value, 0, alarmNumber, 0, Math.min(value.length, alarmNumber.length));
        data.writeBytes(alarmNumber);
        data.writeZero(16); // reserved
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
                    boolean jimiOnlineCommand = deviceType == DEVICE_TYPE_JC181
                            || deviceType == DEVICE_TYPE_JC371
                            || deviceType == DEVICE_TYPE_JC450;
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
                    encodeVideoRequestData(data, server, port, channel);
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
                    encodeVideoListData(
                            data,
                            command.getInteger(Command.KEY_INDEX),
                            command.getString(Command.KEY_START_TIME),
                            command.getString(Command.KEY_END_TIME),
                            command.getLong(Command.KEY_ALARM_FLAG),
                            videoTimeZone(command.getDeviceId()));
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_VIDEO_LIST, id, false, data);
                case Command.TYPE_VIDEO_PLAYBACK:
                    String playbackServer = command.getString(Command.KEY_SERVER);
                    int playbackPort = command.getInteger(Command.KEY_PORT);
                    data.writeByte(playbackServer.length());
                    data.writeCharSequence(playbackServer, StandardCharsets.US_ASCII);
                    data.writeShort(playbackPort);
                    data.writeShort(0);
                    data.writeByte(command.getInteger(Command.KEY_INDEX));
                    data.writeByte(command.getInteger(Command.KEY_RESOURCE_TYPE));
                    data.writeByte(command.getInteger(Command.KEY_STREAM_TYPE));
                    data.writeByte(command.getInteger(Command.KEY_STORAGE_TYPE));
                    data.writeByte(0); // normal playback
                    data.writeByte(0); // normal speed
                    TimeZone playbackTimeZone = videoTimeZone(command.getDeviceId());
                    writeDate(data, command.getString(Command.KEY_START_TIME), playbackTimeZone);
                    writeDate(data, command.getString(Command.KEY_END_TIME), playbackTimeZone);
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_VIDEO_PLAYBACK, id, false, data);
                case Command.TYPE_VIDEO_UPLOAD:
                    int networkCondition = encodeVideoUploadData(
                            data, command, videoTimeZone(command.getDeviceId()));
                    LOGGER.error(
                            "Huabao video upload encoded deviceId={} server={} port={} channel={} network=0x{}",
                            command.getDeviceId(), command.getString(Command.KEY_SERVER),
                            command.getInteger(Command.KEY_PORT), command.getInteger(Command.KEY_INDEX),
                            Integer.toHexString(networkCondition).toUpperCase());
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_VIDEO_UPLOAD, id, false, data);
                case Command.TYPE_GET_DEVICE_STATUS:
                    // No data: 0x8104 query all terminal parameters (empty body).
                    // Data present: comma-separated parameter ids -> 0x8106 query specific.
                    // Either way the device replies with 0x0104.
                    String parameterIds = command.getString(Command.KEY_DATA);
                    if (parameterIds != null && !parameterIds.isEmpty()) {
                        encodeQuerySpecificParameterData(data, parseParameterIds(parameterIds));
                        return HuabaoProtocolDecoder.formatMessage(
                                HuabaoProtocolDecoder.MSG_QUERY_SPECIFIC_PARAMETERS, id, false, data);
                    }
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_QUERY_PARAMETERS, id, false, data);
                case Command.TYPE_GET_VERSION:
                    // 0x8107 query terminal attributes, empty body; device replies with 0x0107
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_QUERY_ATTRIBUTES, id, false, data);
                case Command.TYPE_CONFIGURATION:
                    // 0x8103 set terminal parameters, single entry:
                    // count(1) + parameter id(4) + parameter length(1) + value(length)
                    int parameterId = command.getInteger(Command.KEY_INDEX);
                    byte[] parameterValue = DataConverter.parseHex(command.getString(Command.KEY_DATA));
                    encodeSetParameterData(data, parameterId, parameterValue);
                    LOGGER.error(
                            "Huabao set parameter encoded deviceId={} id=0x{} length={}",
                            command.getDeviceId(), Integer.toHexString(parameterId).toUpperCase(),
                            parameterValue.length);
                    return HuabaoProtocolDecoder.formatMessage(
                            HuabaoProtocolDecoder.MSG_SET_PARAMETERS, id, false, data);
                default:
                    return null;
            }
        } finally {
            id.release();
        }
    }

}
