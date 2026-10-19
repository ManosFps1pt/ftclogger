package io.github.manosfps1pt.ftclogger.debug;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorImplEx;
import dev.nextftc.hardware.RobotController;
import dev.nextftc.hardware.actuators.NextMotor;
import dev.nextftc.hardware.lynx.NextLynxModule;
import dev.nextftc.units.Units;

/** Owns one open-loop NextMotor. Updates only this instance, never the global NextFTC motor loop. */
public final class NextMotorAdapter implements FeedforwardMotor,AutoCloseable {
    private final NextLynxModule module;
    private final DcMotorImplEx sdk;
    private final NextMotor motor;
    private final DcMotor.RunMode savedMode;
    private final DcMotor.ZeroPowerBehavior savedZero;
    private final double radiansPerTick;
    private boolean closed;

    public NextMotorAdapter(MotorTarget target,FeedforwardConfig config) {
        this(resolve(target),target.port,config);
    }
    private static NextLynxModule resolve(MotorTarget target) {
        return target.hub==MotorTarget.Hub.CONTROL_HUB?RobotController.controlHub():RobotController.expansionHub();
    }
    private NextMotorAdapter(NextLynxModule module,int port,FeedforwardConfig config) {
        // Resolving the controller here also detects an absent hub before accepting the run.
        this(module,new DcMotorImplEx(module.getMotorController(),port),config);
    }
    NextMotorAdapter(NextLynxModule module,DcMotorImplEx sdk,FeedforwardConfig config) {
        this.module=module; this.sdk=sdk;
        savedMode=sdk.getMode(); savedZero=sdk.getZeroPowerBehavior();
        radiansPerTick=config.radiansPerTick();
        motor=new NextMotor(() -> sdk,Units.getRadians(radiansPerTick),0.0);
        try {
            sdk.setPower(0); sdk.setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
            motor.setZeroPowerBehavior(NextMotor.ZeroPowerBehavior.FLOAT);
            power(0);
        } catch (RuntimeException error) { close(); throw error; }
    }
    public NextMotor motor() { return motor; }
    @Override public Reading read() {
        return new Reading(module.getInputVoltage().getBaseUnitMagnitude(),motor.getCurrent().getBaseUnitMagnitude(),
                Math.round(motor.getEncoderPosition().getBaseUnitMagnitude()/radiansPerTick),
                motor.getEncoderVelocity().getBaseUnitMagnitude()/radiansPerTick,motor.getThrottle());
    }
    @Override public void power(double duty) {
        if (closed && duty!=0) throw new IllegalStateException("Motor adapter is closed");
        motor.setThrottle(duty);
        try { motor.update(); }
        finally {
            // A stop must reach the controller even if the wrapper update failed.
            if (duty==0) sdk.setPower(0);
        }
    }
    @Override public void close() {
        if (closed) return;
        try { power(0); }
        finally {
            closed=true;
            try { sdk.setMode(savedMode); }
            finally { try { sdk.setZeroPowerBehavior(savedZero); } finally { sdk.setPower(0); } }
        }
    }
}
