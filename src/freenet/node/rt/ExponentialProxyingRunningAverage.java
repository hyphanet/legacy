package freenet.node.rt;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.FieldSet;

/**
 * Exponential Running Average implemented as a wrapper around a regular
 * RA. We use log and exp on report and guess respectively.
 */
public class ExponentialProxyingRunningAverage implements RunningAverage {

    final RunningAverage ra;
    
    public Object clone() {
        return new ExponentialProxyingRunningAverage(this);
    }
    
    public ExponentialProxyingRunningAverage(RunningAverage ra) {
        this.ra = ra;
    }

    public double currentValue() {
        return Math.exp(ra.currentValue());
    }

    public void report(double d) {
        ra.report(Math.log(d));
    }

    public void report(long d) {
        report((double)d);
    }

    public FieldSet toFieldSet() {
        return null;
    }

    public double valueIfReported(double r) {
        return Math.exp(ra.valueIfReported(Math.log(r)));
    }

    public long countReports() {
        return ra.countReports();
    }

    public void writeDataTo(DataOutputStream out) throws IOException {
        throw new UnsupportedOperationException();
    }

    public int getDataLength() {
        return 0;
    }

}
