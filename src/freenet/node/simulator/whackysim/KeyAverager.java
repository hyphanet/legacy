/*
 * Running average for keys. Takes into account circularity
 * of keyspace.
 */
package freenet.node.simulator.whackysim;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Random;

import freenet.FieldSet;
import freenet.Key;
import freenet.node.rt.RunningAverage;

public class KeyAverager implements RunningAverage {

    double value;
    
    public KeyAverager(KeyAverager averager) {
        this.value = averager.value;
    }

    /**
     * @param r
     */
    public KeyAverager(Random r) {
        value = r.nextDouble() * Key.KEYSPACE_SIZE_DOUBLE;
    }

    public Object clone() {
        return new KeyAverager(this);
    }

    public double currentValue() {
        return value;
    }

    public void report(double d) {
        double forwardDistance = d - value;
        if(forwardDistance < 0)
            forwardDistance += Key.KEYSPACE_SIZE_DOUBLE;
        double backwardDistance = value - d;
        if(backwardDistance < 0)
            backwardDistance += Key.KEYSPACE_SIZE_DOUBLE;
        if(forwardDistance < backwardDistance) {
            value += forwardDistance / 100;
        } else {
            value -= backwardDistance / 100;
        }
        if(value > Key.KEYSPACE_SIZE_DOUBLE)
            value -= Key.KEYSPACE_SIZE_DOUBLE;
        if(value < 0)
            value += Key.KEYSPACE_SIZE_DOUBLE;
    }

    public void report(long d) {
        throw new UnsupportedOperationException();
    }

    public FieldSet toFieldSet() {
        throw new UnsupportedOperationException();
    }

    public double valueIfReported(double r) {
        throw new UnsupportedOperationException();
    }

    public long countReports() {
        throw new UnsupportedOperationException();
    }

    public void writeDataTo(DataOutputStream out) throws IOException {
        throw new UnsupportedOperationException();
    }

    public int getDataLength() {
        throw new UnsupportedOperationException();
    }

}
