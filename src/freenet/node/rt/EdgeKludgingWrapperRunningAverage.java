package freenet.node.rt;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.Core;
import freenet.FieldSet;
import freenet.support.Logger;

public class EdgeKludgingWrapperRunningAverage implements RunningAverage {

    final int maxReports;
    final RunningAverage ra;

    public EdgeKludgingWrapperRunningAverage(RunningAverage ra, int maxReports) {
        this.ra = ra;
        this.maxReports = maxReports;
    }

    public Object clone() {
        return new EdgeKludgingWrapperRunningAverage((RunningAverage)ra.clone(), maxReports);
    }

    public double currentValue() {
        return kludge(ra.currentValue());
    }

    /**
     * @param d
     * @return
     */
    private double kludge(double d) {
        if(d > 1.0) {
            Core.logger.log(this, "Too high probability: "+d+
                    " while kludging "+this, new Exception("debug"), 
                    Logger.ERROR);
            d = 1.0;
        }
        if(d < 0.0) {
            Core.logger.log(this, "Too low probability: "+d+
                    " while kludging "+this, new Exception("debug"), 
                    Logger.ERROR);
        }
        if(d != 0.0 && d != 1.0) return d;
        double offset = 1.0 / (countReports() + maxReports + 1);
        if(d == 0.0)
            return offset;
        return 1.0 - offset;
    }

    public void report(double d) {
        ra.report(d);
    }

    public void report(long d) {
        ra.report(d);
    }

    public FieldSet toFieldSet() {
        return ra.toFieldSet();
    }

    public double valueIfReported(double r) {
        return kludge(ra.valueIfReported(r));
    }

    public long countReports() {
        return ra.countReports();
    }

    public void writeDataTo(DataOutputStream out) throws IOException {
        ra.writeDataTo(out);
    }

    public int getDataLength() {
        return ra.getDataLength();
    }
    
    public String toString() {
        return getClass().getName()+" "+ra.toString();
    }
    
}
