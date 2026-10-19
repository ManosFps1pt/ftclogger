package io.github.manosfps1pt.ftclogger.debug;

import android.os.SystemClock;
import io.github.manosfps1pt.ftclogger.protocol.*;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.LongSupplier;

/** One motor, chosen by hub/port at run time. Settings are frozen for the entire run. */
public final class FeedforwardTool implements SelectableOpMode.Tool {
    public static final String ID="motor.feedforward.v1";
    private final String mechanismId,label;
    private final BiFunction<MotorTarget,FeedforwardConfig,NextMotorAdapter> motorFactory;
    private final LongSupplier clock;
    private final Set<String> executed=new HashSet<>();
    private SelectableOpMode.ToolContext context;
    private NextMotorAdapter adapter;
    private FeedforwardBenchmark run;
    private FeedforwardConfig config;
    private DebugRunHeader header;
    private DebugRunTransfer transfer;
    private long lastStatus;
    private long stationarySince=-1,lastPosition;
    private boolean stationary,adapterClosed;
    private String runId="";

    public FeedforwardTool(String mechanismId,String label) { this(mechanismId,label,NextMotorAdapter::new,SystemClock::elapsedRealtimeNanos); }
    FeedforwardTool(String mechanismId,String label,BiFunction<MotorTarget,FeedforwardConfig,NextMotorAdapter> factory,LongSupplier clock) {
        this.mechanismId=mechanismId; this.label=label; motorFactory=factory; this.clock=clock;
    }
    /** Register the calculator without copying protocol or lifecycle boilerplate into TeamCode. */
    public static void register(SelectableOpMode.FolderBuilder folder,String id) {
        folder.tool(id,"kS / kV Calculator","Characterize one motor selected by hub and port.",
                DebugRiskClass.DEBUG_FREE_SPIN,1,true,"",()->new FeedforwardTool(id,"Motor Feedforward"),Collections.emptyList(),
                Arrays.asList(new SelectableOpMode.Command("benchmark.run","Run","Start characterization",false),
                        new SelectableOpMode.Command("benchmark.abort","Abort","Stop and retain acquired data",false),
                        new SelectableOpMode.Command("benchmark.keepalive","Keepalive","Maintain operator presence",false)));
    }
    private static DebugCommandArgumentDefinition numeric(String id,String label,ValueType type,double min,double max,Double value,String unit) {
        DebugCommandArgumentDefinition.Builder d=DebugCommandArgumentDefinition.newBuilder().setId(id).setLabel(label)
                .setValueType(type).setMinFloat64(min).setMaxFloat64(max).setRequired(value==null).setUnit(unit);
        if (value!=null) {
            DebugCommandArgumentValue.Builder v=DebugCommandArgumentValue.newBuilder().setId(id);
            if (type==ValueType.INT64) v.setInt64Value(value.longValue()); else v.setFloat64Value(value);
            d.setDefaultValue(v);
        }
        return d.build();
    }
    private static DebugCommandArgumentDefinition choice(String id,String label,String defaultId,String... options) {
        DebugCommandArgumentDefinition.Builder d=DebugCommandArgumentDefinition.newBuilder().setId(id).setLabel(label).setValueType(ValueType.ENUM)
                .setRequired(true)
                .setDefaultValue(DebugCommandArgumentValue.newBuilder().setId(id).setEnumValue(defaultId));
        for (int i=0;i<options.length;i+=2) d.addEnumOptions(DebugEnumOption.newBuilder().setId(options[i]).setLabel(options[i+1]));
        return d.build();
    }
    public static DebugBenchmarkDefinition definition() {
        DebugBenchmarkDefinition.Builder b=DebugBenchmarkDefinition.newBuilder().setId(ID).setVersion(1).setLabel("kS / kV Calculator")
                .setDescription("Measure breakaway kS, then fit V − kS against angular velocity through the origin.").addAnalyzerIds("feedforward.v1");
        b.addInputs(choice("hub","Hub","control_hub","control_hub","Control Hub","expansion_hub","Expansion Hub"));
        b.addInputs(numeric("port","Motor port",ValueType.INT64,0,3,null,""));
        b.addInputs(choice("direction","Direction","forward","forward","Forward","reverse","Reverse"));
        b.addInputs(numeric("ticks_per_revolution","Encoder counts per shaft revolution (including gearing)",ValueType.FLOAT64,0.000001,1e9,null,"tick/rev"));
        b.addInputs(numeric("current_limit_amps","Current cutoff",ValueType.FLOAT64,0.1,100,null,"A"));
        b.addInputs(numeric("steps","Power steps",ValueType.INT64,3,100,20.0,""));
        b.addInputs(numeric("max_power","Maximum power",ValueType.FLOAT64,0.01,1,1.0,"normalized"));
        b.addInputs(numeric("settle_seconds","Settling time",ValueType.FLOAT64,0,30,3.0,"s"));
        b.addInputs(numeric("sample_seconds","Averaging window",ValueType.FLOAT64,0.2,10,1.0,"s"));
        b.addInputs(numeric("sample_period_seconds","Acquisition interval",ValueType.FLOAT64,0.01,0.1,0.02,"s"));
        b.addInputs(numeric("breakaway_volts_per_second","Breakaway ramp rate",ValueType.FLOAT64,0.01,3,0.25,"V/s"));
        b.addInputs(numeric("breakaway_max_volts","Breakaway voltage ceiling",ValueType.FLOAT64,0.1,12,3.0,"V"));
        b.addInputs(numeric("motion_ticks","Movement threshold",ValueType.INT64,1,10000,3.0,"tick"));
        b.addInputs(numeric("motion_seconds","Sustained movement time",ValueType.FLOAT64,0.02,2,0.15,"s"));
        b.addInputs(numeric("stability_tolerance","Relative velocity SD / drift limit",ValueType.FLOAT64,0.001,1,0.05,"fraction"));
        b.addInputs(numeric("min_velocity_ticks_per_second","Minimum moving velocity",ValueType.FLOAT64,0.01,1000,5.0,"tick/s"));
        b.addInputs(numeric("reference_voltage","NextMotor gain reference voltage",ValueType.FLOAT64,1,16,12.0,"V"));
        b.addAllChannels(FrictionTool.definition(false).getChannelsList());
        return b.build();
    }
    /** Strict robot-side validation, independent of the laptop's form validation. */
    public static Map<String,DebugCommandArgumentValue> validateInputs(List<DebugCommandArgumentValue> inputs) {
        Map<String,DebugCommandArgumentValue> values=new LinkedHashMap<>();
        Map<String,DebugCommandArgumentDefinition> definitions=new LinkedHashMap<>();
        for (DebugCommandArgumentDefinition d:definition().getInputsList()) definitions.put(d.getId(),d);
        for (DebugCommandArgumentValue v:inputs) {
            if (!definitions.containsKey(v.getId()) || values.put(v.getId(),v)!=null) throw new IllegalArgumentException("Unknown or duplicate input: "+v.getId());
        }
        for (DebugCommandArgumentDefinition d:definitions.values()) {
            DebugCommandArgumentValue v=values.get(d.getId());
            if (v==null) {
                if (!d.hasDefaultValue()) throw new IllegalArgumentException("Required input: "+d.getId());
                v=d.getDefaultValue(); values.put(d.getId(),v);
            }
            if (d.getValueType()==ValueType.ENUM) {
                boolean valid=false;
                for (DebugEnumOption option:d.getEnumOptionsList()) if (v.hasEnumValue() && option.getId().equals(v.getEnumValue())) valid=true;
                if (!valid) throw new IllegalArgumentException("Invalid choice: "+d.getId());
            } else {
                if ((d.getValueType()==ValueType.INT64 && !v.hasInt64Value()) || (d.getValueType()==ValueType.FLOAT64 && !v.hasFloat64Value()))
                    throw new IllegalArgumentException("Invalid type: "+d.getId());
                FeedforwardConfig.range(v.hasInt64Value()?v.getInt64Value():v.getFloat64Value(),d.getMinFloat64(),d.getMaxFloat64(),d.getId());
            }
        }
        return values;
    }
    private static double n(Map<String,DebugCommandArgumentValue> values,String id) {
        DebugCommandArgumentValue v=values.get(id); return v.hasInt64Value()?v.getInt64Value():v.getFloat64Value();
    }
    public static FeedforwardConfig configuration(Map<String,DebugCommandArgumentValue> a) {
        return FeedforwardConfig.builder(n(a,"ticks_per_revolution"),n(a,"current_limit_amps")).steps((int)n(a,"steps"))
                .maxPower(n(a,"max_power")).settleSeconds(n(a,"settle_seconds")).sampleSeconds(n(a,"sample_seconds"))
                .samplePeriodSeconds(n(a,"sample_period_seconds")).breakawayVoltsPerSecond(n(a,"breakaway_volts_per_second"))
                .breakawayMaxVolts(n(a,"breakaway_max_volts")).motionTicks((int)n(a,"motion_ticks")).motionSeconds(n(a,"motion_seconds"))
                .stabilityTolerance(n(a,"stability_tolerance")).minVelocityTicksPerSecond(n(a,"min_velocity_ticks_per_second"))
                .referenceVoltage(n(a,"reference_voltage")).build();
    }
    @Override public void init(SelectableOpMode.ToolContext c) { context=c; }
    @Override public void start() { }
    @Override public List<DebugBenchmarkDefinition> benchmarks() { return Collections.singletonList(definition()); }
    @Override public DebugCommandResponse command(DebugCommandRequest request) {
        String cmd=request.getCommandId();
        if (!cmd.startsWith("benchmark.")) return null;
        DebugCommandResponse.Builder response=DebugCommandResponse.newBuilder().setRequestId(request.getRequestId()).setCommandId(cmd)
                .setHandledAtRobotTimeNs(clock.getAsLong());
        try {
            if (cmd.equals("benchmark.keepalive") || cmd.equals("benchmark.abort")) {
                if (run==null || !request.getRequestId().equals(runId)) throw new IllegalArgumentException("Run is not active");
                if (cmd.endsWith("keepalive")) run.keepalive(clock.getAsLong()); else stopOutput();
                return response.setResult(DebugCommandResult.DEBUG_COMMAND_COMPLETED).build();
            }
            if (!cmd.equals("benchmark.run")) return null;
            UUID.fromString(request.getRequestId());
            if (executed.contains(request.getRequestId())) return response.setResult(DebugCommandResult.DEBUG_COMMAND_DUPLICATE).setMessage("Run already accepted; it will not execute again").build();
            if (busy()) throw new IllegalArgumentException("Previous run is active, transferring, or still moving");
            if (!context.dataClient.serverSupportsBenchmarks()) throw new IllegalStateException("Laptop must support debug-runs-v1");
            Map<String,DebugCommandArgumentValue> a=validateInputs(request.getArgumentsList());
            FeedforwardConfig nextConfig=configuration(a);
            MotorTarget target=new MotorTarget(a.get("hub").getEnumValue().equals("control_hub")?MotorTarget.Hub.CONTROL_HUB:MotorTarget.Hub.EXPANSION_HUB,(int)n(a,"port"));
            int direction=a.get("direction").getEnumValue().equals("forward")?1:-1;
            if (adapter!=null) adapter.close();
            adapter=null; run=null; transfer=null;
            adapter=motorFactory.apply(target,nextConfig);
            adapterClosed=false; stationary=false; stationarySince=-1;
            long now=clock.getAsLong();
            config=nextConfig; run=new FeedforwardBenchmark(adapter,config,direction,now);
            runId=request.getRequestId(); executed.add(runId); transfer=null;
            header=DebugRunHeader.newBuilder().setRunId(runId).setMechanismId(mechanismId+"."+target.toString())
                    .setMechanismLabel(label+" "+target).setMotorName(target.toString()).setBenchmark(definition()).addAllInputs(a.values())
                    .setTicksPerRevolution(config.ticksPerRevolution).setStartedRobotNs(now).setMaxPower(config.maxPower).setCurrentLimitAmps(config.currentLimitAmps).build();
            return response.setResult(DebugCommandResult.DEBUG_COMMAND_ACCEPTED).setMessage("Characterization started").build();
        } catch (RuntimeException error) {
            if ((run==null || !run.running()) && adapter!=null) adapter.close();
            return response.setResult(DebugCommandResult.DEBUG_COMMAND_REJECTED).setMessage(error.getMessage()==null?error.getClass().getSimpleName():error.getMessage()).build();
        }
    }
    @Override public void loop() {
        if (run==null) return;
        long now=clock.getAsLong();
        if (run.running() && !context.dataClient.isConnected()) run.abort("TCP connection lost");
        run.tick(now);
        if (!run.running() && transfer==null) {
            List<DebugRunSample> rows=new ArrayList<>();
            for (FeedforwardSample s:run.completedDataset()) rows.add(encode(rows.size(),s));
            transfer=new DebugRunTransfer(header,rows,run.outcome(),run.message());
        }
        if (!run.running() && !adapterClosed) {
            try {
                FeedforwardMotor.Reading reading=adapter.read();
                if (Double.isFinite(reading.velocityTicksPerSecond) && Math.abs(reading.velocityTicksPerSecond)<config.minVelocityTicksPerSecond
                        && (stationarySince<0 || Math.abs(reading.positionTicks-lastPosition)<config.motionTicks)) {
                    if (stationarySince<0) { stationarySince=now; lastPosition=reading.positionTicks; }
                    if (now-stationarySince>=1_000_000_000L) { adapter.close(); adapterClosed=true; stationary=true; }
                } else stationarySince=-1;
            } catch (RuntimeException error) { stationarySince=-1; }
        }
        if (now-lastStatus>=200_000_000L) {
            lastStatus=now;
            context.dataClient.publishMessage(Envelope.newBuilder().setDebugRunStatus(DebugRunStatus.newBuilder().setRunId(runId)
                    .setState(run.running()?"running":transfer.acknowledged()?"received":"transferring").setPhase(run.phase())
                    .setRepetition(1).setDirection(run.direction()).setMessage(run.running() && run.operatingPoint()>0
                            ? "Power point "+run.operatingPoint()+" / "+config.steps : run.message())));
        }
        if (transfer!=null) transfer.tick(context.dataClient,now);
    }
    private static ChannelValue number(int id,double v) { return ChannelValue.newBuilder().setChannelId(id).setFloat64Value(v).build(); }
    private static DebugRunSample encode(int sequence,FeedforwardSample s) {
        return DebugRunSample.newBuilder().setSequence(sequence).setRobotTimeNs(s.robotTimeNs).setPhase(s.phase)
                .setRepetition(1).setDirection(s.direction).setOperatingPoint(s.point)
                .addValues(number(1,s.reading.voltage)).addValues(number(2,s.reading.appliedDuty)).addValues(number(3,s.reading.currentAmps))
                .addValues(ChannelValue.newBuilder().setChannelId(4).setInt64Value(s.reading.positionTicks))
                .addValues(number(5,s.reading.velocityTicksPerSecond)).addValues(number(6,s.estimatedVoltage()))
                .addValues(ChannelValue.newBuilder().setChannelId(7).setBooleanValue(s.movementOnset))
                .addValues(ChannelValue.newBuilder().setChannelId(8).setBooleanValue(s.phase.equals("sample"))).build();
    }
    @Override public void receive(Envelope e) { if (transfer!=null) transfer.receive(e); }
    @Override public boolean busy() {
        if (run==null) return false;
        if (run.running() || transfer==null || !transfer.acknowledged()) return true;
        return !stationary;
    }
    @Override public void stopOutput() { if (run!=null) run.abort("Operator aborted characterization"); if (adapter!=null) adapter.power(0); }
    @Override public void stop() { try { stopOutput(); } finally { if (adapter!=null) adapter.close(); } }
    @Override public SelectableOpMode.ParameterResult setParameter(String id,double v,int ttl) { return SelectableOpMode.ParameterResult.rejected("READ_ONLY","Configure inputs before starting a run"); }
    @Override public double requestedOutput() { return run==null?0:run.duty(); }
    @Override public double appliedOutput() { return adapter==null?0:adapter.motor().getThrottle(); }
    @Override public long encoderPosition() { return adapter==null?0:adapter.read().positionTicks; }
    @Override public boolean outputAllowed() { return run!=null && run.running(); }
    @Override public boolean watchdogHealthy() { return run==null || !run.message().contains("keepalive"); }
}
