/*
 * Created on Feb 6, 2004
 *  
 */
package freenet.node.rt;

import java.io.DataOutputStream;
import java.io.IOException;

import freenet.FieldSet;

/**
 * @author Iakin A wrapper that takes a RunningAverage and makes it threadsafe
 * See the Collections.SynchronizedXXX documentation for a better
 * description of the functionality
 */
public class SynchronizedRunningAverage implements RunningAverage {
	private final RunningAverage r;
	
	public Object clone() {
	    return new SynchronizedRunningAverage((RunningAverage)r.clone());
	}
	
	public SynchronizedRunningAverage(RunningAverage r) {
		this.r = r;
	}
	public double currentValue() {
		synchronized (r) {
			return r.currentValue();
		}
	}

	public void report(double d) {
		synchronized (r) {
			r.report(d);
		}

	}

	public void report(long d) {
		synchronized (r) {
			r.report(d);
		}
	}

	public FieldSet toFieldSet() {
		synchronized (r) {
			return r.toFieldSet();
		}
	}

	public double valueIfReported(double r) {
		synchronized (this.r) {
			return this.r.valueIfReported(r);
		}
	}

	public void writeDataTo(DataOutputStream out) throws IOException {
		synchronized (r) {
			r.writeDataTo(out);
		}
	}

	public int getDataLength() {
		synchronized (r) {
			return r.getDataLength();
		}
	}

    public long countReports() {
        synchronized(r) {
            return r.countReports();
        }
    }
}
