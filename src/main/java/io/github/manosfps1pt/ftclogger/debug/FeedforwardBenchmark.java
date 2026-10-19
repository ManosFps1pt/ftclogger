package io.github.manosfps1pt.ftclogger.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Nonblocking acquisition. Supply monotonic nanoseconds and refresh operator presence while running. */
public final class FeedforwardBenchmark implements Benchmark<FeedforwardSample> {
    private final FeedforwardMotor motor;
    private final FeedforwardConfig config;
    private final int direction;
    private final List<FeedforwardSample> samples=new ArrayList<>();
    private String phase="rest",outcome="running",message="";
    private long phaseStart,lastSample,lastTick,lastKeepalive,restSince=-1,origin,onsetSince=-1;
    private int point,onsetIndex=-1;
    private double requestedDuty,rampVoltage,candidateVoltage;
    private boolean originSet;

    public FeedforwardBenchmark(FeedforwardMotor motor,FeedforwardConfig config,int direction,long now) {
        if (motor==null || config==null || (direction!=1 && direction!=-1)) throw new IllegalArgumentException("Motor, settings, and direction are required");
        this.motor=motor; this.config=config; this.direction=direction;
        phaseStart=now; lastTick=now; lastKeepalive=now; lastSample=now-(long)(config.samplePeriodSeconds*1e9);
        motor.power(0);
    }
    @Override public boolean running() { return outcome.equals("running"); }
    @Override public String phase() { return phase; }
    @Override public String outcome() { return outcome; }
    public String message() { return message; }
    public int operatingPoint() { return point; }
    public int direction() { return direction; }
    public double duty() { return requestedDuty; }
    @Override public void keepalive(long now) { if (now>=lastKeepalive) lastKeepalive=now; }
    @Override public List<FeedforwardSample> completedDataset() {
        if (running()) throw new IllegalStateException("Acquisition is still running");
        return Collections.unmodifiableList(samples);
    }
    public FeedforwardResult result() { return FeedforwardResult.analyze(completedDataset(),config); }
    @Override public void abort(String reason) { if (running()) finish("aborted",reason); else power(0); }
    private void power(double duty) { requestedDuty=duty; motor.power(duty); }
    private void finish(String result,String reason) { outcome=result; message=reason; power(0); }
    private void transition(String next,long now) { phase=next; phaseStart=now; restSince=-1; originSet=false; }
    private void startPoint(long now) { transition("settle",now); power(direction*config.maxPower*point/config.steps); }
    public void tick(long now) {
        if (!running()) return;
        try { acquire(now); }
        catch (RuntimeException error) { finish("aborted","Sensor or motor failure: "+error.getClass().getSimpleName()); }
    }
    private void acquire(long now) {
        if (now<lastTick) { abort("Robot clock moved backwards"); return; }
        lastTick=now;
        if (now-lastKeepalive>2_000_000_000L) { abort("Operator keepalive expired"); return; }
        if (now-lastSample<(long)(config.samplePeriodSeconds*1e9)) return;
        double dt=(now-lastSample)/1e9; lastSample=now;
        FeedforwardMotor.Reading r=motor.read();
        if (r==null || !Double.isFinite(r.voltage) || r.voltage<=0 || !Double.isFinite(r.currentAmps) || r.currentAmps<0
                || !Double.isFinite(r.velocityTicksPerSecond) || !Double.isFinite(r.appliedDuty) || Math.abs(r.appliedDuty)>config.maxPower+1e-9) {
            abort("Invalid voltage, current, encoder, or applied output"); return;
        }
        samples.add(new FeedforwardSample(now,phase,point,direction,r,false));
        if (r.currentAmps>config.currentLimitAmps) { abort("Current cutoff exceeded"); return; }
        if (samples.size()>=FeedforwardConfig.MAX_SAMPLES) { abort("Acquisition capacity reached"); return; }
        double elapsed=(now-phaseStart)/1e9;
        if (phase.equals("rest") || phase.equals("coast")) {
            if (!originSet) { origin=r.positionTicks; originSet=true; }
            if (Math.abs(r.velocityTicksPerSecond)<config.minVelocityTicksPerSecond && Math.abs(r.positionTicks-origin)<config.motionTicks) {
                if (restSince<0) restSince=now;
                if ((now-restSince)/1e9>=FeedforwardConfig.REST_SECONDS) {
                    if (phase.equals("coast")) { finish("completed","Characterization complete"); return; }
                    transition("ramp",now); origin=r.positionTicks; originSet=true; rampVoltage=0; return;
                }
            } else { restSince=-1; origin=r.positionTicks; }
            double timeout=phase.equals("rest")?FeedforwardConfig.REST_TIMEOUT_SECONDS:FeedforwardConfig.STOP_TIMEOUT_SECONDS;
            if (elapsed>=timeout) finish("inconclusive","Motor did not reach rest within the timeout");
        } else if (phase.equals("ramp")) {
            boolean moving=(r.positionTicks-origin)*direction>=config.motionTicks && r.velocityTicksPerSecond*direction>0;
            if (moving) {
                if (onsetSince<0) { onsetSince=now; onsetIndex=samples.size()-1; candidateVoltage=Math.abs(r.appliedDuty*r.voltage); }
                if ((now-onsetSince)/1e9>=config.motionSeconds) {
                    FeedforwardSample first=samples.get(onsetIndex);
                    samples.set(onsetIndex,new FeedforwardSample(first.robotTimeNs,first.phase,first.point,first.direction,first.reading,true));
                    point=1; startPoint(now); return;
                }
                power(direction*Math.min(config.maxPower,candidateVoltage/r.voltage));
            } else {
                onsetSince=-1; onsetIndex=-1;
                rampVoltage=Math.min(config.breakawayMaxVolts,rampVoltage+config.breakawayVoltsPerSecond*dt);
                power(direction*Math.min(config.maxPower,rampVoltage/r.voltage));
            }
            if (elapsed>config.breakawayMaxVolts/config.breakawayVoltsPerSecond+config.motionSeconds+1)
                finish("inconclusive","Movement not observed within the breakaway limit");
        } else if (phase.equals("settle")) {
            if (elapsed>=config.settleSeconds) transition("sample",now);
        } else if (phase.equals("sample")) {
            if (elapsed>=config.sampleSeconds) {
                if (point<config.steps) { point++; startPoint(now); }
                else { power(0); transition("coast",now); }
            }
        }
    }
}
