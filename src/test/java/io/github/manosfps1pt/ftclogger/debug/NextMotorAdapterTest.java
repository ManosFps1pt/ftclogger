package io.github.manosfps1pt.ftclogger.debug;

import com.qualcomm.robotcore.hardware.DcMotor;
import com.qualcomm.robotcore.hardware.DcMotorImplEx;
import dev.nextftc.hardware.actuators.NextMotor;
import dev.nextftc.hardware.lynx.NextLynxModule;
import dev.nextftc.units.Units;
import io.github.manosfps1pt.ftclogger.StructuredRobotDataClient;
import io.github.manosfps1pt.ftclogger.protocol.*;
import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NextMotorAdapterTest {
    @Test public void finePowerChangesAndZeroReachHardwareAndConfigurationIsRestored() {
        try (org.mockito.MockedStatic<android.util.Log> logging=mockStatic(android.util.Log.class)) {
        DcMotorImplEx sdk=mock(DcMotorImplEx.class); NextLynxModule hub=mock(NextLynxModule.class);
        when(sdk.getMode()).thenReturn(DcMotor.RunMode.RUN_USING_ENCODER);
        when(sdk.getZeroPowerBehavior()).thenReturn(DcMotor.ZeroPowerBehavior.BRAKE);
        when(hub.getInputVoltage()).thenReturn(Units.getVolts(11));
        NextMotorAdapter adapter=new NextMotorAdapter(hub,sdk,FeedforwardBenchmarkTest.config());
        adapter.power(.0001); adapter.power(.0002);
        verify(sdk).setPower(.0001); verify(sdk).setPower(.0002);
        assertEquals(.0002,adapter.read().appliedDuty,0);
        assertEquals(11,adapter.read().voltage,0);
        adapter.close();
        verify(sdk).setMode(DcMotor.RunMode.RUN_WITHOUT_ENCODER);
        verify(sdk).setMode(DcMotor.RunMode.RUN_USING_ENCODER);
        verify(sdk).setZeroPowerBehavior(DcMotor.ZeroPowerBehavior.BRAKE);
        assertThrows(IllegalStateException.class,()->adapter.power(.1));
        }
    }
    private static DebugCommandRequest request() {
        return DebugCommandRequest.newBuilder().setRequestId(UUID.randomUUID().toString()).setCommandId("benchmark.run")
                .addArguments(DebugCommandArgumentValue.newBuilder().setId("port").setInt64Value(1))
                .addArguments(DebugCommandArgumentValue.newBuilder().setId("ticks_per_revolution").setFloat64Value(600))
                .addArguments(DebugCommandArgumentValue.newBuilder().setId("current_limit_amps").setFloat64Value(7)).build();
    }
    private static StructuredRobotDataClient client() {
        StructuredRobotDataClient client=mock(StructuredRobotDataClient.class);
        when(client.serverSupportsBenchmarks()).thenReturn(true); return client;
    }
    @Test public void missingHubRejectsWithoutLockingOutRetry() {
        AtomicInteger attempts=new AtomicInteger();
        FeedforwardTool tool=new FeedforwardTool("test","Test",(target,config)-> { attempts.incrementAndGet(); throw new NoSuchElementException("Expansion Hub missing"); },()->0);
        tool.init(new SelectableOpMode.ToolContext(null,null,"test","instance",client()));
        for(int i=0;i<2;i++) {
            assertEquals(DebugCommandResult.DEBUG_COMMAND_REJECTED,tool.command(request()).getResult());
            assertFalse(tool.busy());
        }
        assertEquals(2,attempts.get());
    }
    @Test public void duplicateRunAndLostConnectionNeverReActuate() {
        NextMotorAdapter motor=mock(NextMotorAdapter.class); when(motor.motor()).thenReturn(mock(NextMotor.class));
        when(motor.read()).thenReturn(new FeedforwardMotor.Reading(12,1,0,0,0));
        AtomicInteger creations=new AtomicInteger(); StructuredRobotDataClient client=client();
        FeedforwardTool tool=new FeedforwardTool("test","Test",(target,config)-> { creations.incrementAndGet(); return motor; },()->0);
        tool.init(new SelectableOpMode.ToolContext(null,null,"test","instance",client));
        DebugCommandRequest request=request();
        assertEquals(DebugCommandResult.DEBUG_COMMAND_ACCEPTED,tool.command(request).getResult());
        assertEquals(DebugCommandResult.DEBUG_COMMAND_DUPLICATE,tool.command(request).getResult());
        tool.loop(); // Disconnected: abort and retain, without driving another point.
        assertEquals(1,creations.get()); verify(motor,atLeastOnce()).power(0);
        verify(motor,never()).power(doubleThat(v->v!=0));
        tool.stop(); verify(motor).close();
    }
}
