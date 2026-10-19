package io.github.manosfps1pt.ftclogger.debug;

/** Physical port identity, independent of the team's hardware configuration names. */
public final class MotorTarget {
    public enum Hub { CONTROL_HUB, EXPANSION_HUB }
    public final Hub hub;
    public final int port;
    public MotorTarget(Hub hub,int port) {
        if (hub==null) throw new IllegalArgumentException("Select a hub");
        FeedforwardConfig.integer(port,0,3,"port"); this.hub=hub; this.port=port;
    }
    @Override public String toString() { return hub.name()+":"+port; }
}
