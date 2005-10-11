package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;

import freenet.FieldSet;


/**
 * @author amphibian
 * 
 * Factory for BootstrappingDecayingRunningAverage's.
 */
public class BootstrappingDecayingRunningAverageFactory implements
        RunningAverageFactory {

    double min;
    double max;
    int maxReports;
    
    /**
     * Constructor.
     */
    public BootstrappingDecayingRunningAverageFactory(double min, double max,
            int maxReports) {
        this.min = min;
        this.max = max;
        this.maxReports = maxReports;
    }

    public RunningAverage create(double start) {
        return new BootstrappingDecayingRunningAverage(start, min, max, maxReports);
    }

    public RunningAverage create(DataInputStream dis) throws IOException {
        return new BootstrappingDecayingRunningAverage(dis, min, max, maxReports);
    }

    public RunningAverage create(FieldSet set) throws EstimatorFormatException {
        return new BootstrappingDecayingRunningAverage(set, min, max, maxReports);
    }

}
