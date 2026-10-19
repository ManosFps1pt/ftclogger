package io.github.manosfps1pt.ftclogger.debug;

/** Immutable settings shared by acquisition and offline analysis. Encoder calibration includes gearing. */
public final class FeedforwardConfig {
    public final double ticksPerRevolution, currentLimitAmps, maxPower, settleSeconds, sampleSeconds;
    public final double samplePeriodSeconds, breakawayVoltsPerSecond, breakawayMaxVolts;
    public final double motionSeconds, stabilityTolerance, minVelocityTicksPerSecond, referenceVoltage;
    public final int steps, motionTicks;
    public static final int MAX_SAMPLES = 30_000;
    public static final double REST_SECONDS = 1, REST_TIMEOUT_SECONDS = 5, STOP_TIMEOUT_SECONDS = 15;

    private FeedforwardConfig(Builder b) {
        ticksPerRevolution=range(b.ticksPerRevolution, 0.000001, 1e9, "ticksPerRevolution");
        currentLimitAmps=range(b.currentLimitAmps, 0.1, 100, "currentLimitAmps");
        steps=integer(b.steps, 3, 100, "steps");
        maxPower=range(b.maxPower, 0.01, 1, "maxPower");
        settleSeconds=range(b.settleSeconds, 0, 30, "settleSeconds");
        sampleSeconds=range(b.sampleSeconds, 0.2, 10, "sampleSeconds");
        samplePeriodSeconds=range(b.samplePeriodSeconds, 0.01, 0.1, "samplePeriodSeconds");
        breakawayVoltsPerSecond=range(b.breakawayVoltsPerSecond, 0.01, 3, "breakawayVoltsPerSecond");
        breakawayMaxVolts=range(b.breakawayMaxVolts, 0.1, 12, "breakawayMaxVolts");
        motionTicks=integer(b.motionTicks, 1, 10000, "motionTicks");
        motionSeconds=range(b.motionSeconds, 0.02, 2, "motionSeconds");
        stabilityTolerance=range(b.stabilityTolerance, 0.001, 1, "stabilityTolerance");
        minVelocityTicksPerSecond=range(b.minVelocityTicksPerSecond, 0.01, 1000, "minVelocityTicksPerSecond");
        referenceVoltage=range(b.referenceVoltage, 1, 16, "referenceVoltage");
        double duration=REST_TIMEOUT_SECONDS + breakawayMaxVolts/breakawayVoltsPerSecond
                + motionSeconds + steps*(settleSeconds+sampleSeconds+2*samplePeriodSeconds) + STOP_TIMEOUT_SECONDS;
        if (sampleSeconds < 3*samplePeriodSeconds || duration/samplePeriodSeconds >= MAX_SAMPLES)
            throw new IllegalArgumentException("Settings exceed acquisition capacity or provide too few samples per window");
    }
    static double range(double v,double min,double max,String name) {
        if (!Double.isFinite(v) || v<min || v>max) throw new IllegalArgumentException(name+" must be in ["+min+", "+max+"]");
        return v;
    }
    static int integer(int v,int min,int max,String name) { range(v,min,max,name); return v; }
    public double radiansPerTick() { return 2*Math.PI/ticksPerRevolution; }
    public static Builder builder(double ticksPerRevolution,double currentLimitAmps) { return new Builder(ticksPerRevolution,currentLimitAmps); }

    public static final class Builder {
        private final double ticksPerRevolution,currentLimitAmps;
        private int steps=20,motionTicks=3;
        private double maxPower=1,settleSeconds=3,sampleSeconds=1,samplePeriodSeconds=0.02;
        private double breakawayVoltsPerSecond=0.25,breakawayMaxVolts=3,motionSeconds=0.15;
        private double stabilityTolerance=0.05,minVelocityTicksPerSecond=5,referenceVoltage=12;
        private Builder(double ticks,double current) { ticksPerRevolution=ticks; currentLimitAmps=current; }
        public Builder steps(int v) { steps=v; return this; }
        public Builder maxPower(double v) { maxPower=v; return this; }
        public Builder settleSeconds(double v) { settleSeconds=v; return this; }
        public Builder sampleSeconds(double v) { sampleSeconds=v; return this; }
        public Builder samplePeriodSeconds(double v) { samplePeriodSeconds=v; return this; }
        public Builder breakawayVoltsPerSecond(double v) { breakawayVoltsPerSecond=v; return this; }
        public Builder breakawayMaxVolts(double v) { breakawayMaxVolts=v; return this; }
        public Builder motionTicks(int v) { motionTicks=v; return this; }
        public Builder motionSeconds(double v) { motionSeconds=v; return this; }
        public Builder stabilityTolerance(double v) { stabilityTolerance=v; return this; }
        public Builder minVelocityTicksPerSecond(double v) { minVelocityTicksPerSecond=v; return this; }
        public Builder referenceVoltage(double v) { referenceVoltage=v; return this; }
        public FeedforwardConfig build() { return new FeedforwardConfig(this); }
    }
}
