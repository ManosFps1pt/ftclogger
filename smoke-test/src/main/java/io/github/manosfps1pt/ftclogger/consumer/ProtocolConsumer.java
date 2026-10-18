package io.github.manosfps1pt.ftclogger.consumer;

import com.google.protobuf.ByteString;
import com.qualcomm.robotcore.hardware.Gamepad;
import io.github.manosfps1pt.ftclogger.StructuredRobotDataClient;
import io.github.manosfps1pt.ftclogger.protocol.Envelope;
import io.github.manosfps1pt.ftclogger.protocol.Event;

/** Compiles only when the published POM exposes the required API dependencies. */
public final class ProtocolConsumer {
    public static void publish(StructuredRobotDataClient client, Gamepad gamepad) {
        client.publishGamepads(gamepad, gamepad);
        client.publishMessage(Envelope.newBuilder()
                .setSessionId(ByteString.copyFrom(new byte[16]))
                .setEvent(Event.newBuilder().setName("consumer-check")));
    }
}
