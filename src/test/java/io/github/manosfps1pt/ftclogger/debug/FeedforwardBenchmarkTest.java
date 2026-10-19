package io.github.manosfps1pt.ftclogger.debug;

import io.github.manosfps1pt.ftclogger.StructuredRobotDataClient;
import io.github.manosfps1pt.ftclogger.protocol.*;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class FeedforwardBenchmarkTest {
    static class Plant implements FeedforwardMotor {
        double duty,voltage=12,current=1,position;
        boolean disconnected,stalled,invalid,reverseEncoder;
        int writes;
        public Reading read() {
            if (disconnected) throw new IllegalStateException("Sensor disconnected");
            double speed=stalled?0:Math.copySign(Math.max(0,(Math.abs(duty)*voltage-0.4)/0.006),duty);
            if (reverseEncoder) speed=-speed;
            position+=speed*.02;
            return new Reading(voltage,current,(long)position,invalid?Double.NaN:speed,duty);
        }
        public void power(double v) { duty=v; writes++; }
    }
    static FeedforwardConfig config() {
        return FeedforwardConfig.builder(600,7).steps(4).settleSeconds(.2).sampleSeconds(.2).breakawayVoltsPerSecond(3).build();
    }
    static FeedforwardBenchmark run(Plant motor,int direction) {
        FeedforwardBenchmark run=new FeedforwardBenchmark(motor,config(),direction,0);
        for(long now=0;run.running() && now<30_000_000_000L;now+=20_000_000L) { run.keepalive(now); run.tick(now); }
        return run;
    }
    @Test public void procedureCompletesAndSamplesPrecedingCommands() {
        Plant motor=new Plant(); FeedforwardBenchmark run=run(motor,1);
        assertEquals(run.message(),"completed",run.outcome()); assertEquals(0,motor.duty,0);
        for(FeedforwardSample s:run.completedDataset()) if(s.phase.equals("sample")) assertEquals(s.point/4.0,s.reading.appliedDuty,1e-12);
        FeedforwardResult result=run.result(); assertNotNull(result.kVVoltsPerRadianPerSecond);
        assertEquals(4,result.points.size()); assertTrue(result.kSVolts>=.4 && result.kSVolts<1);
    }
    @Test public void reverseUsesDirectionalFeedbackAndPositiveGains() {
        FeedforwardBenchmark run=run(new Plant(),-1);
        assertEquals("completed",run.outcome()); assertTrue(run.result().kVVoltsPerRadianPerSecond>0);
    }
    @Test public void noisyOrMissingMotionCannotProduceKs() {
        Plant stalled=new Plant(); stalled.stalled=true;
        FeedforwardBenchmark run=run(stalled,1);
        assertEquals("inconclusive",run.outcome()); assertNull(run.result().kSVolts); assertEquals(0,stalled.duty,0);
        Plant wrongDirection=new Plant(); wrongDirection.reverseEncoder=true;
        assertEquals("inconclusive",run(wrongDirection,1).outcome());
    }
    @Test public void isolatedEncoderJitterDoesNotCountAsSustainedMovement() {
        Plant noise=new Plant() {
            int index;
            @Override public Reading read() {
                return new Reading(12,1,duty==0?0:(index++%2),duty==0?0:.5,duty);
            }
        };
        FeedforwardBenchmark run=run(noise,1);
        assertEquals("inconclusive",run.outcome());
        assertTrue(run.message().contains("Movement not observed")); assertNull(run.result().kSVolts);
    }
    @Test public void faultsAndExpiredPresenceStopImmediately() {
        for(int fault=0;fault<4;fault++) {
            Plant p=new Plant(); FeedforwardBenchmark run=new FeedforwardBenchmark(p,config(),1,0);
            p.duty=.3;
            if(fault==0) p.current=8;
            if(fault==1) p.invalid=true;
            if(fault==2) p.disconnected=true;
            run.tick(fault==3?2_000_000_001L:0);
            assertEquals("aborted",run.outcome()); assertEquals(0,p.duty,0);
        }
    }
    @Test public void abortIsIdempotentAndDoesNotResume() {
        Plant p=new Plant(); FeedforwardBenchmark run=new FeedforwardBenchmark(p,config(),1,0);
        run.abort("Stop"); run.tick(20_000_000); run.abort("Stop again");
        assertEquals("Stop",run.message()); assertEquals(0,p.duty,0);
    }
    @Test public void candidateVoltageIsHeldWhileMovementIsConfirmed() {
        Plant p=new Plant(); FeedforwardBenchmark run=new FeedforwardBenchmark(p,config(),1,0);
        boolean held=false; double previous=-1;
        for(long now=0;run.running() && now<5_000_000_000L;now+=20_000_000L) {
            run.keepalive(now); run.tick(now);
            if(run.phase().equals("ramp") && p.duty>0 && p.duty==previous) held=true;
            previous=p.duty;
        }
        assertTrue(held);
    }
    @Test public void sharedFixtureRecoversCoefficientsAndExcludesUnstableWindow() throws Exception {
        List<FeedforwardSample> rows=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new InputStreamReader(Objects.requireNonNull(getClass().getResourceAsStream("/feedforward.csv")),StandardCharsets.UTF_8))) {
            reader.readLine(); String line;
            while((line=reader.readLine())!=null) {
                String[] c=line.split(",");
                rows.add(new FeedforwardSample(Long.parseLong(c[0]),c[1],Integer.parseInt(c[2]),Integer.parseInt(c[3]),
                        new FeedforwardMotor.Reading(Double.parseDouble(c[4]),Double.parseDouble(c[6]),Long.parseLong(c[7]),Double.parseDouble(c[8]),Double.parseDouble(c[5])),Boolean.parseBoolean(c[9])));
            }
        }
        FeedforwardResult result=FeedforwardResult.analyze(rows,FeedforwardConfig.builder(600,7).steps(5).build());
        assertEquals(.4,result.kSVolts,1e-12);
        assertEquals(.006*600/(2*Math.PI),result.kVVoltsPerRadianPerSecond,1e-12);
        assertEquals(1,result.rSquared,1e-12); assertFalse(result.points.get(4).included());
        assertEquals(.006*10/12,result.nextMotorGains(FeedforwardResult.SpeedUnit.RPM,12).kV,1e-12);
        assertEquals(result.kVVoltsPerRadianPerSecond/12*Math.PI/180,result.nextMotorGains(FeedforwardResult.SpeedUnit.DEGREES_PER_SECOND,12).kV,1e-12);
        assertNull(FeedforwardResult.analyze(rows.subList(0,20),config()).kVVoltsPerRadianPerSecond);
    }
    @Test public void configurationAndTypedInputsRejectInvalidValues() {
        for(double v:new double[]{0,-1,Double.NaN,Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class,()->FeedforwardConfig.builder(v,7).build());
        }
        assertThrows(IllegalArgumentException.class,()->new MotorTarget(MotorTarget.Hub.CONTROL_HUB,4));
        assertThrows(IllegalArgumentException.class,()->FeedforwardConfig.builder(600,7).steps(100).settleSeconds(30).build());
        assertThrows(IllegalArgumentException.class,()->FeedforwardTool.validateInputs(Collections.emptyList()));
        List<DebugCommandArgumentValue> inputs=Arrays.asList(
                DebugCommandArgumentValue.newBuilder().setId("port").setInt64Value(0).build(),
                DebugCommandArgumentValue.newBuilder().setId("ticks_per_revolution").setFloat64Value(600).build(),
                DebugCommandArgumentValue.newBuilder().setId("current_limit_amps").setFloat64Value(7).build());
        assertEquals(20,FeedforwardTool.configuration(FeedforwardTool.validateInputs(inputs)).steps);
        List<DebugCommandArgumentValue> duplicate=new ArrayList<>(inputs); duplicate.add(inputs.get(0));
        assertThrows(IllegalArgumentException.class,()->FeedforwardTool.validateInputs(duplicate));
    }
    @Test public void correctedOriginFitRetainsStablePointsBelowBreakaway() {
        List<FeedforwardSample> rows=new ArrayList<>();
        rows.add(new FeedforwardSample(0,"ramp",0,1,new FeedforwardMotor.Reading(12,1,3,5,2.0/12),true));
        for(int point=1;point<=4;point++) for(int i=0;i<=50;i++) {
            rows.add(new FeedforwardSample(point*2_000_000_000L+i*20_000_000L,"sample",point,1,
                    new FeedforwardMotor.Reading(12,1,i,point*100,(.4+.6*point)/12),false));
        }
        FeedforwardResult result=FeedforwardResult.analyze(rows,FeedforwardConfig.builder(600,7).steps(4).build());
        assertTrue(result.points.stream().allMatch(FeedforwardResult.Point::included));
        assertEquals(.2/Math.PI,result.kVVoltsPerRadianPerSecond,1e-12);
        assertFalse(result.reliable);
    }
    @Test public void retainedTransferRetriesWithoutActuationAndHonorsAckCount() {
        StructuredRobotDataClient client=mock(StructuredRobotDataClient.class);
        when(client.isConnected()).thenReturn(true); when(client.connectionGeneration()).thenReturn(1L,1L,2L);
        when(client.publishReliableMessage(any())).thenReturn(true);
        DebugRunTransfer transfer=new DebugRunTransfer(DebugRunHeader.newBuilder().setRunId("run").build(),Collections.emptyList(),"completed","");
        transfer.tick(client,0); transfer.tick(client,1); transfer.tick(client,2);
        verify(client,times(3)).publishReliableMessage(any());
        transfer.receive(Envelope.newBuilder().setDebugRunAck(DebugRunAck.newBuilder().setRunId("run").setTotalSamples(1)).build());
        assertFalse(transfer.acknowledged());
        transfer.receive(Envelope.newBuilder().setDebugRunAck(DebugRunAck.newBuilder().setRunId("run")).build());
        assertTrue(transfer.acknowledged()); transfer.tick(client,3);
        verify(client,times(3)).publishReliableMessage(any());
    }
}
