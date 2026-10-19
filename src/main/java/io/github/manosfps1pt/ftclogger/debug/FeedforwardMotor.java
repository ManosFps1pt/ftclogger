package io.github.manosfps1pt.ftclogger.debug;

/** All calls occur on the owning OpMode thread. power() must apply its command before returning. */
public interface FeedforwardMotor {
    Reading read();
    void power(double duty);

    final class Reading {
        public final double voltage,currentAmps,velocityTicksPerSecond,appliedDuty;
        public final long positionTicks;
        public Reading(double voltage,double currentAmps,long positionTicks,double velocityTicksPerSecond,double appliedDuty) {
            this.voltage=voltage; this.currentAmps=currentAmps; this.positionTicks=positionTicks;
            this.velocityTicksPerSecond=velocityTicksPerSecond; this.appliedDuty=appliedDuty;
        }
    }
}
