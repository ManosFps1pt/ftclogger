package io.github.manosfps1pt.ftclogger.debug;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Deterministic, equally weighted operating-point regression; also usable with saved acquisition rows. */
public final class FeedforwardResult {
    public enum SpeedUnit {
        RADIANS_PER_SECOND(1), RPM(60/(2*Math.PI)), DEGREES_PER_SECOND(180/Math.PI);
        public final double unitsPerRadian;
        SpeedUnit(double scale) { unitsPerRadian=scale; }
    }
    public static final class Gains {
        public final double kS,kV,referenceVoltage;
        public final SpeedUnit speedUnit;
        private Gains(double ks,double kv,double voltage,SpeedUnit unit) { kS=ks; kV=kv; referenceVoltage=voltage; speedUnit=unit; }
    }
    public static final class Point {
        public final int index,count;
        public final double speedRadiansPerSecond,voltage,speedStd,drift,currentAmps;
        public final String exclusion;
        Point(int index,int count,double speed,double voltage,double std,double drift,double current,String exclusion) {
            this.index=index; this.count=count; speedRadiansPerSecond=speed; this.voltage=voltage;
            speedStd=std; this.drift=drift; currentAmps=current; this.exclusion=exclusion;
        }
        public boolean included() { return exclusion.isEmpty(); }
    }
    public final Double kSVolts,kVVoltsPerRadianPerSecond,rSquared,rmseVolts;
    public final List<Point> points;
    public final boolean reliable;
    private FeedforwardResult(Double ks,Double kv,Double r2,Double rmse,List<Point> points) {
        kSVolts=ks; kVVoltsPerRadianPerSecond=kv; rSquared=r2; rmseVolts=rmse;
        this.points=Collections.unmodifiableList(points);
        reliable=kv!=null && kv>0 && r2!=null && r2>=0.9;
    }
    public Gains nextMotorGains(SpeedUnit unit,double referenceVoltage) {
        FeedforwardConfig.range(referenceVoltage,1,16,"referenceVoltage");
        if (unit==null || kSVolts==null || kVVoltsPerRadianPerSecond==null) throw new IllegalStateException("A valid fit and speed unit are required");
        return new Gains(kSVolts/referenceVoltage,kVVoltsPerRadianPerSecond/referenceVoltage/unit.unitsPerRadian,referenceVoltage,unit);
    }
    public static FeedforwardResult analyze(List<FeedforwardSample> samples,FeedforwardConfig config) {
        Double ks=null;
        for (FeedforwardSample s:samples) if (s.movementOnset) { ks=Math.abs(s.estimatedVoltage()); break; }
        List<Point> points=new ArrayList<>();
        for (int point=1;point<=config.steps;point++) {
            List<FeedforwardSample> rows=new ArrayList<>();
            for (FeedforwardSample s:samples) if (s.point==point && s.phase.equals("sample")) rows.add(s);
            if (rows.isEmpty()) continue;
            double sum=0,sum2=0,voltage=0,current=0;
            for (FeedforwardSample s:rows) {
                double v=s.reading.velocityTicksPerSecond*s.direction;
                sum+=v; sum2+=v*v; voltage+=Math.abs(s.estimatedVoltage()); current+=s.reading.currentAmps;
            }
            int n=rows.size(); double mean=sum/n,std=Math.sqrt(Math.max(0,sum2/n-mean*mean));
            FeedforwardSample first=rows.get(0),last=rows.get(n-1);
            double drift=Math.abs(last.reading.velocityTicksPerSecond-first.reading.velocityTicksPerSecond);
            String reason="";
            if (n<3 || (last.robotTimeNs-first.robotTimeNs)/1e9 < config.sampleSeconds-1.5*config.samplePeriodSeconds) reason="Incomplete sampling window";
            else if (mean<config.minVelocityTicksPerSecond) reason="Stationary or wrong direction";
            else if (std>mean*config.stabilityTolerance || drift>mean*config.stabilityTolerance) reason="Unstable velocity";
            points.add(new Point(point,n,mean*config.radiansPerTick(),voltage/n,std*config.radiansPerTick(),drift*config.radiansPerTick(),current/n,reason));
        }
        double xx=0,xy=0,totalV=0; int n=0;
        Set<Long> distinct=new HashSet<>();
        for (Point p:points) if (p.included()) {
            n++; distinct.add(Math.round(p.voltage*1000)); totalV+=p.voltage;
            xx+=p.speedRadiansPerSecond*p.speedRadiansPerSecond;
            if (ks!=null) xy+=p.speedRadiansPerSecond*(p.voltage-ks);
        }
        if (ks==null || distinct.size()<3 || xx<=1e-12) return new FeedforwardResult(ks,null,null,null,points);
        double kv=xy/xx,sse=0,sst=0;
        for (Point p:points) if (p.included()) {
            double error=p.voltage-ks-kv*p.speedRadiansPerSecond;
            sse+=error*error; sst+=Math.pow(p.voltage-totalV/n,2);
        }
        return new FeedforwardResult(ks,kv,sst>1e-12?1-sse/sst:null,Math.sqrt(sse/n),points);
    }
}
