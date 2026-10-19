package io.github.manosfps1pt.ftclogger.debug;

/** Immutable acquisition row. The reading describes the preceding applied command. */
public final class FeedforwardSample {
    public final long robotTimeNs;
    public final String phase;
    public final int point,direction;
    public final FeedforwardMotor.Reading reading;
    public final boolean movementOnset;
    public FeedforwardSample(long time,String phase,int point,int direction,FeedforwardMotor.Reading reading,boolean onset) {
        robotTimeNs=time; this.phase=phase; this.point=point; this.direction=direction;
        this.reading=reading; movementOnset=onset;
    }
    public double estimatedVoltage() { return reading.voltage*reading.appliedDuty; }
}
