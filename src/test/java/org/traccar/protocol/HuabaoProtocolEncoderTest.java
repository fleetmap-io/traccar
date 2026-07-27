package org.traccar.protocol;

import static org.junit.Assert.assertEquals;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
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
