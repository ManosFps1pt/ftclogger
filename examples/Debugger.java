package org.firstinspires.ftc.teamcode;

import com.qualcomm.robotcore.eventloop.opmode.TeleOp;
import io.github.manosfps1pt.ftclogger.debug.FeedforwardTool;
import io.github.manosfps1pt.ftclogger.debug.SelectableOpMode;

/** Copy into TeamCode. Hub, port, calibration, and run settings are entered on the laptop. */
@TeleOp(name="FTC Advanced Debugger",group="FTC Advanced")
public final class Debugger extends SelectableOpMode {
    @Override protected void configure(Registry registry) {
        registry.folder("motor-tests","Motor Tests",motors ->
                motors.folder("motor.feedforward","Motor Feedforward",folder ->
                        FeedforwardTool.register(folder,"motor.feedforward.calculator")));
    }
}
