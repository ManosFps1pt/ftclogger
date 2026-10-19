package io.github.manosfps1pt.ftclogger.examples;

import dev.nextftc.hardware.actuators.NextMotor;
import dev.nextftc.units.Units;
import io.github.manosfps1pt.ftclogger.debug.FeedforwardConfig;
import io.github.manosfps1pt.ftclogger.debug.FeedforwardResult;

/** Compiled against the published artifact by smoke-test. Called after reviewing the fit. */
public final class VelocityFeedforwardExample {
    public static void apply(NextMotor motor,FeedforwardResult result,FeedforwardConfig config,double targetRadiansPerSecond) {
        FeedforwardResult.Gains gains=result.nextMotorGains(FeedforwardResult.SpeedUnit.RADIANS_PER_SECOND,config.referenceVoltage);
        motor.setAnglePerCount(Units.getRadians(config.radiansPerTick()));
        motor.getVelocityConstants().withS(gains.kS).withV(gains.kV);
        motor.setVelocitySetpoint(Units.getRadiansPerSecond(targetRadiansPerSecond));
    }
}
