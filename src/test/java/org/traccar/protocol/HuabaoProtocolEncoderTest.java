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
