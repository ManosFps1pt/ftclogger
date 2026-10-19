package io.github.manosfps1pt.ftclogger.debug;

import io.github.manosfps1pt.ftclogger.StructuredRobotDataClient;
import io.github.manosfps1pt.ftclogger.protocol.*;
import java.util.ArrayList;
import java.util.List;

/** Retains an immutable completed dataset until durable laptop acknowledgement; reconnects never actuate hardware. */
public final class DebugRunTransfer {
    private final DebugRunHeader header;
    private final List<DebugRunSample> samples;
    private final String outcome,message;
    private boolean acknowledged;
    private long generation=-1,lastTransfer;
    private int next=-1;
    public DebugRunTransfer(DebugRunHeader header,List<DebugRunSample> samples,String outcome,String message) {
        if (samples.size()>FeedforwardConfig.MAX_SAMPLES) throw new IllegalArgumentException("Too many samples");
        this.header=header; this.samples=new ArrayList<>(samples); this.outcome=outcome; this.message=message;
    }
    public boolean acknowledged() { return acknowledged; }
    public void receive(Envelope envelope) {
        if (envelope.hasDebugRunAck() && envelope.getDebugRunAck().getRunId().equals(header.getRunId())
                && envelope.getDebugRunAck().getTotalSamples()==samples.size()) acknowledged=true;
    }
    public void tick(StructuredRobotDataClient client,long now) {
        if (acknowledged || !client.isConnected()) return;
        int chunks=(samples.size()+127)/128;
        if (generation!=client.connectionGeneration() || (next>chunks && now-lastTransfer>3_000_000_000L)) {
            generation=client.connectionGeneration(); next=-1;
        }
        Envelope.Builder frame=Envelope.newBuilder();
        if (next<0) frame.setDebugRunHeader(header);
        else if (next<chunks) frame.setDebugRunChunk(DebugRunChunk.newBuilder().setRunId(header.getRunId())
                .setChunkIndex(next).addAllSamples(samples.subList(next*128,Math.min(samples.size(),(next+1)*128))));
        else if (next==chunks) frame.setDebugRunEnd(DebugRunEnd.newBuilder().setRunId(header.getRunId())
                .setTotalSamples(samples.size()).setTotalChunks(chunks).setOutcome(outcome).setMessage(message));
        else return;
        if (client.publishReliableMessage(frame)) { next++; lastTransfer=now; }
    }
}
