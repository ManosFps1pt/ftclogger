package io.github.manosfps1pt.ftclogger;

import io.github.manosfps1pt.ftclogger.protocol.*;
import org.junit.Test;
import java.util.Locale;

import static org.junit.Assert.*;

/** Golden wire messages generated with the original FTC Advanced Java bindings. */
public final class ProtocolCompatibilityTest {
    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }

    @Test public void helloMatchesOriginalLaptopProtocol() throws Exception {
        byte[] original = hex("0802200728959aef3a52340a07726f626f742d311a0e54656c656d6574727920546573742880023206736368656d61320e73616d706c652d62617463686573");
        Envelope decoded = Envelope.parseFrom(original);
        assertEquals(2, decoded.getProtocolVersion());
        assertEquals(7L, decoded.getConnectionSequence());
        assertEquals(123456789L, decoded.getRobotElapsedNs());
        assertEquals("robot-1", decoded.getHello().getRobotId());
        assertEquals("Telemetry Test", decoded.getHello().getOpModeName());

        Envelope generated = Envelope.newBuilder().setProtocolVersion(2).setConnectionSequence(7)
                .setRobotElapsedNs(123456789L)
                .setHello(Hello.newBuilder().setRobotId("robot-1").setOpModeName("Telemetry Test")
                        .setQueueCapacity(256).addCapabilities("schema").addCapabilities("sample-batches"))
                .build();
        assertArrayEquals(original, generated.toByteArray());
    }

    @Test public void highlightedPoseSampleMatchesOriginalLaptopProtocol() throws Exception {
        byte[] original = hex("08026a360a34082a10011a0b08011100000000000029401a1f0802421b09000000000000f83f1100000000000002c019000000000000e83f2001");
        Snapshot sample = Envelope.parseFrom(original).getSampleBatch().getSnapshots(0);
        assertEquals(42L, sample.getSampleSequence());
        assertEquals(1, sample.getSchemaRevision());
        assertTrue(sample.getHighlighted());
        assertEquals(12.5, sample.getValues(0).getFloat64Value(), 0.0);
        assertEquals(1.5, sample.getValues(1).getPose2DValue().getX(), 0.0);
        assertEquals(-2.25, sample.getValues(1).getPose2DValue().getY(), 0.0);
        assertEquals(0.75, sample.getValues(1).getPose2DValue().getHeadingRad(), 0.0);

        Envelope generated = Envelope.newBuilder().setProtocolVersion(2)
                .setSampleBatch(SampleBatch.newBuilder().addSnapshots(Snapshot.newBuilder()
                        .setSampleSequence(42).setSchemaRevision(1).setHighlighted(true)
                        .addValues(ChannelValue.newBuilder().setChannelId(1).setFloat64Value(12.5))
                        .addValues(ChannelValue.newBuilder().setChannelId(2).setPose2DValue(
                                Pose2d.newBuilder().setX(1.5).setY(-2.25).setHeadingRad(0.75)))))
                .build();
        assertArrayEquals(original, generated.toByteArray());
    }

    @Test public void signalRegistrationDoesNotDependOnDeviceLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            new StructuredRobotDataClient("Locale Test")
                    .addSignal("battery.voltage", "Voltage", null,
                            "voltage", "V", "float64", "diagnostic", 50);
        } finally {
            Locale.setDefault(previous);
        }
    }

}
