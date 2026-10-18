package io.github.manosfps1pt.ftclogger;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorEx;
import com.qualcomm.robotcore.hardware.Servo;
import com.qualcomm.robotcore.hardware.VoltageSensor;
import dev.nextftc.hardware.actuators.NextMotor;
import dev.nextftc.hardware.actuators.NextRGBIndicator;
import dev.nextftc.units.measuretypes.Angle;
import dev.nextftc.units.measuretypes.AngularVelocity;
import dev.nextftc.units.measuretypes.Current;
import org.firstinspires.ftc.robotcore.external.navigation.CurrentUnit;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public final class HardwareBindingsTest {
    private static void number(Map<String, Object> values, String key, double expected) {
        assertNotNull(key, values.get(key));
        assertEquals(key, expected, ((Number) values.get(key)).doubleValue(), 1e-9);
    }

    private static StructuredRobotDataClient client() {
        VoltageSensor voltage = mock(VoltageSensor.class);
        when(voltage.getVoltage()).thenReturn(12.0);
        return new StructuredRobotDataClient("Bindings Test").addVoltageSensor(voltage);
    }

    private static Angle angle(double radians) {
        Angle angle = mock(Angle.class);
        when(angle.getBaseUnitMagnitude()).thenReturn(radians);
        return angle;
    }

    private static NextMotor nextMotor() {
        NextMotor motor = mock(NextMotor.class);
        doReturn(angle(0.05)).when(motor).getAnglePerCount();
        doReturn(angle(36.25)).when(motor).getEncoderPosition();
        AngularVelocity velocity = mock(AngularVelocity.class);
        when(velocity.getBaseUnitMagnitude()).thenReturn(12.5);
        when(motor.getEncoderVelocity()).thenReturn(velocity);
        Current current = mock(Current.class);
        when(current.getBaseUnitMagnitude()).thenReturn(4.5);
        when(motor.getCurrent()).thenReturn(current);
        when(motor.getControlType()).thenReturn(new NextMotor.ControlType.Throttle(0.8));
        when(motor.getThrottle()).thenReturn(0.25);
        return motor;
    }

    @Test public void originalDcMotorExApiRetainsAllMeasurements() {
        DcMotorEx motor = mock(DcMotorEx.class);
        when(motor.getPower()).thenReturn(0.3);
        when(motor.getCurrentPosition()).thenReturn(-123);
        when(motor.getVelocity()).thenReturn(-45.0);
        when(motor.getCurrent(CurrentUnit.AMPS)).thenReturn(2.0);
        Map<String, Object> values = client().addMotor("drive", "Drive", motor).createDataSnapshot();
        number(values, "drive.commandedPower", 0.3);
        number(values, "drive.appliedPower", 0.3);
        assertEquals(Long.valueOf(-123), values.get("drive.encoderPosition"));
        number(values, "drive.velocityTicksPerSecond", -45.0);
        number(values, "drive.currentAmps", 2.0);
        number(values, "drive.electricalPowerWatts", 24.0);
        verify(motor, never()).setPower(anyDouble());
    }

    @Test public void originalRequestedPowerOverloadIsPreserved() {
        DcMotorEx motor = mock(DcMotorEx.class);
        when(motor.getPower()).thenReturn(0.3);
        Map<String, Object> values = client().addMotor("drive", "Drive", motor, () -> 0.9).createDataSnapshot();
        number(values, "drive.commandedPower", 0.9);
        number(values, "drive.appliedPower", 0.3);
    }

    @Test public void plainDcMotorKeepsUnsupportedMeasurementsUnavailable() {
        DcMotor motor = mock(DcMotor.class);
        when(motor.getPower()).thenReturn(-0.4);
        when(motor.getCurrentPosition()).thenReturn(17);
        Map<String, Object> values = client().addMotor("basic", "Basic", motor).createDataSnapshot();
        number(values, "basic.commandedPower", -0.4);
        assertEquals(Long.valueOf(17), values.get("basic.encoderPosition"));
        for (String suffix : new String[]{"velocityTicksPerSecond", "currentAmps", "electricalPowerWatts"}) {
            assertTrue(values.containsKey("basic." + suffix));
            assertNull(values.get("basic." + suffix));
        }
    }

    @Test public void sdkBaseTypeStillDetectsExtendedHardware() {
        DcMotorEx extended = mock(DcMotorEx.class);
        when(extended.getCurrent(CurrentUnit.AMPS)).thenReturn(1.5);
        Map<String, Object> values = client().addMotor("motor", "Motor", (DcMotor) extended, () -> 0.7).createDataSnapshot();
        number(values, "motor.currentAmps", 1.5);
        number(values, "motor.commandedPower", 0.7);
    }

    @Test public void nextMotorUsesPublicUnitsAndSeparatesRequestFromCachedOutput() {
        NextMotor motor = nextMotor();
        StructuredRobotDataClient client = client().addMotor("intake", "Intake", motor);
        Map<String, Object> values = client.createDataSnapshot();
        number(values, "intake.commandedPower", 0.8);
        number(values, "intake.appliedPower", 0.25);
        assertEquals(Long.valueOf(725), values.get("intake.encoderPosition"));
        number(values, "intake.velocityTicksPerSecond", 250.0);
        number(values, "intake.currentAmps", 4.5);
        number(values, "intake.electricalPowerWatts", 54.0);
        doReturn(angle(0.1)).when(motor).getAnglePerCount();
        number(client.createDataSnapshot(), "intake.velocityTicksPerSecond", 125.0);
        verify(motor, never()).update();
        verify(motor, never()).setThrottle(anyDouble());
    }

    @Test public void nextMotorCustomRequestAndSaturationAreReported() {
        NextMotor motor = nextMotor();
        when(motor.getThrottle()).thenReturn(1.4);
        Map<String, Object> values = client().addMotor("intake", "Intake", motor, () -> 0.6).createDataSnapshot();
        number(values, "intake.commandedPower", 0.6);
        number(values, "intake.appliedPower", 1.0);
    }

    @Test public void nextClosedLoopModeUsesLatestOutputWithoutChangingMode() {
        NextMotor motor = nextMotor();
        doReturn(new NextMotor.ControlType.Position(angle(1.0))).when(motor).getControlType();
        Map<String, Object> values = client().addMotor("lift", "Lift", motor).createDataSnapshot();
        number(values, "lift.commandedPower", 0.25);
        number(values, "lift.appliedPower", 0.25);
        verify(motor, never()).update();
    }

    @Test public void nextInvalidConversionScaleDoesNotInventEncoderReadings() {
        for (double scale : new double[]{0.0, Double.NaN, Double.POSITIVE_INFINITY}) {
            NextMotor motor = nextMotor();
            doReturn(angle(scale)).when(motor).getAnglePerCount();
            Map<String, Object> values = client().addMotor("motor", "Motor", motor).createDataSnapshot();
            assertNull(values.get("motor.encoderPosition"));
            assertNull(values.get("motor.velocityTicksPerSecond"));
            number(values, "motor.currentAmps", 4.5);
            verify(motor, never()).getEncoderPosition();
            verify(motor, never()).getEncoderVelocity();
        }
    }

    @Test public void rgbServoAndNextIndicatorUseExistingDashboardSignal() {
        Servo servo = mock(Servo.class);
        NextRGBIndicator next = mock(NextRGBIndicator.class);
        when(servo.getPosition()).thenReturn(0.5);
        when(next.getPosition()).thenReturn(0.279);
        StructuredRobotDataClient client = client()
                .addRgbIndicator("indicator.sdk", "SDK Light", servo)
                .addRgbIndicator("indicator.next", "Next Light", next);
        verifyNoInteractions(servo, next);
        Map<String, Object> values = client.createDataSnapshot();
        number(values, "indicator.sdk.dutyCycle", 0.5);
        number(values, "indicator.next.dutyCycle", 0.279);
        when(next.getPosition()).thenReturn(0.611);
        number(client.createDataSnapshot(), "indicator.next.dutyCycle", 0.611);
        verify(servo, never()).setPosition(anyDouble());
        verify(next, never()).setPosition(anyDouble());
        verify(next, never()).setBrightness(anyDouble());
    }

    @Test public void rgbSupplierSupportsMocksAndUnavailableCommands() {
        AtomicReference<Double> position = new AtomicReference<>(0.722);
        StructuredRobotDataClient client = client().addRgbIndicator("light", "Light", () -> position.get());
        number(client.createDataSnapshot(), "light.dutyCycle", 0.722);
        for (double invalid : new double[]{Double.NaN, -0.1, 1.1}) {
            position.set(invalid);
            assertNull(client.createDataSnapshot().get("light.dutyCycle"));
        }
    }

    @Test public void allMotorAndIndicatorTypesCanShareOneSnapshot() {
        StructuredRobotDataClient client = client()
                .addMotor("plain", "Plain", mock(DcMotor.class))
                .addMotor("extended", "Extended", mock(DcMotorEx.class))
                .addMotor("next", "Next", nextMotor())
                .addRgbIndicator("light", "Light", () -> 1.0);
        Map<String, Object> values = client.createDataSnapshot();
        assertEquals(20, values.size()); // voltage + 3 * 6 motor channels + RGB command
        number(values, "light.dutyCycle", 1.0);
    }
}
