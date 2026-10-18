package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.Servo;
import dev.nextftc.hardware.actuators.NextMotor;
import dev.nextftc.hardware.actuators.NextRGBIndicator;
import io.github.manosfps1pt.ftclogger.StructuredRobotDataClient;

/** Call from your initialized OpMode or NextFTC mechanism with your existing devices. */
public final class HardwareBindingsExample {
    public static StructuredRobotDataClient configure(
            DcMotor basic, DcMotorEx extended, NextMotor nextMotor,
            Servo sdkRgbIndicator, NextRGBIndicator nextIndicator) {
        return new StructuredRobotDataClient("Hardware Bindings Example")
                .addMotor("drive.basic", "Basic Motor", basic)
                .addMotor("drive.extended", "Extended Motor", extended)
                .addMotor("intake", "NextFTC Intake", nextMotor)
                .addRgbIndicator("indicator.sdk", "SDK RGB Indicator", sdkRgbIndicator)
                .addRgbIndicator("indicator.next", "NextFTC RGB Indicator", nextIndicator);
    }

    // The OpMode owns start(), publishLoop(), and close(). NextFTC owns motor updates.
    // Call publishLoop() after NextFTC has updated its motor outputs for that loop.
}
