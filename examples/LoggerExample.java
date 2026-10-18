package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.OpMode;
import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import com.qualcomm.robotcore.util.ElapsedTime;

import io.github.manosfps1pt.ftclogger.StructuredRobotDataClient;

/** Copy this file into TeamCode; it is not included in the published library. */
@TeleOp(name = "FTC Logger Example", group = "Examples")
public final class LoggerExample extends OpMode {
    private final ElapsedTime runtime = new ElapsedTime();
    private StructuredRobotDataClient logger;

    @Override public void init() {
        logger = new StructuredRobotDataClient("FTC Logger Example")
                .addRuntime(runtime)
                .addGamepads(gamepad1, gamepad2);
        logger.start();
    }

    @Override public void start() {
        runtime.reset();
    }

    @Override public void loop() {
        logger.publishLoop();
        telemetry.addData("Logger", logger.isConnected() ? "Connected" : "Connecting");
        telemetry.addData("Dropped frames", logger.getDroppedPacketCount());
        telemetry.update();
    }

    @Override public void stop() {
        if (logger != null) logger.close();
    }
}
