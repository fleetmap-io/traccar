package org.traccar.protocol;

import static org.junit.Assert.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import org.junit.Ignore;
import org.junit.Test;
import org.traccar.ProtocolTest;
import org.traccar.model.Command;

public class HuabaoProtocolEncoderTest extends ProtocolTest {

    @Test
    public void testEncodeImeiTerminalId() {
        ByteBuf id = HuabaoProtocolEncoder.encodeTerminalId("860112070346616");
        try {
            assertEquals("4e3a0b712725", ByteBufUtil.hexDump(id));
        } finally {
            id.release();
        }
    }

    @Test
    public void testEncodeVideoRequestIncludesAudio() {
        ByteBuf data = Unpooled.buffer();
        try {
            HuabaoProtocolEncoder.encodeVideoRequestData(data, "media.example", 10002, 2);
            assertEquals("0d6d656469612e6578616d706c6527120000020000", ByteBufUtil.hexDump(data));
        } finally {
            data.release();
        }
    }

    @Test
    public void testEncodeVideoListNoAlarmFilter() {
        ByteBuf data = Unpooled.buffer();
        try {
            HuabaoProtocolEncoder.encodeVideoListData(
                    data, 0, "2026-08-31T09:29:16.000Z", "2026-08-31T09:29:36.000Z", 0);
            assertEquals("002608310929162608310929360000000000000000020000", ByteBufUtil.hexDump(data));
        } finally {
            data.release();
        }
    }

    @Test
    public void testEncodeVideoListWithAlarmFilter() {
        ByteBuf data = Unpooled.buffer();
        try {
            HuabaoProtocolEncoder.encodeVideoListData(
                    data, 3, "2026-08-31T09:29:16.000Z", "2026-08-31T09:29:36.000Z", 0x100000000L);
            assertEquals("032608310929162608310929360000000100000000020000", ByteBufUtil.hexDump(data));
        } finally {
            data.release();
        }
    }

    @Test
    public void testEncodeSetParameter() {
        ByteBuf data = Unpooled.buffer();
        try {
            HuabaoProtocolEncoder.encodeSetParameterData(
                    data, 0x0079, ByteBufUtil.decodeHexDump("ffffff"));
            assertEquals("010000007903ffffff", ByteBufUtil.hexDump(data));
        } finally {
            data.release();
        }
    }

    @Test
    public void testEncodeTransparentJimiText() {
        ByteBuf id = HuabaoProtocolEncoder.encodeTerminalId("869247060610265");
        try {
            ByteBuf frame = HuabaoProtocolEncoder.encodeTransparent(id, "DMSSEP,1,1#", 0xF0);
            try {
                assertEquals(
                        "7e8900000c4f0ebc3a1ae20001f0444d535345502c312c3123747e",
                        ByteBufUtil.hexDump(frame));
            } finally {
                frame.release();
            }
        } finally {
            id.release();
        }
    }

    @Test
    public void testEncodeQuerySpecificParameter() {
        ByteBuf data = Unpooled.buffer();
        try {
            HuabaoProtocolEncoder.encodeQuerySpecificParameterData(
                    data, HuabaoProtocolEncoder.parseParameterIds("64, 65, 0xf364, F365"));
            assertEquals("0400000064000000650000f3640000f365", ByteBufUtil.hexDump(data));
        } finally {
            data.release();
        }
    }

    @Ignore
    @Test
    public void testEncode() throws Exception {

        HuabaoProtocolEncoder encoder = new HuabaoProtocolEncoder(null);

        Command command = new Command();
        command.setDeviceId(1);
        command.setType(Command.TYPE_ENGINE_STOP);

        verifyCommand(encoder, command, binary("7e81050001080201000027001ff0467e"));

    }

}
