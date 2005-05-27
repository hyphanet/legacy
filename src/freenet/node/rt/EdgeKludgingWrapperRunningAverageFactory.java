package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;

import freenet.FieldSet;

public class EdgeKludgingWrapperRunningAverageFactory implements
        RunningAverageFactory {

    final RunningAverageFactory factory;
    final int maxReports;

    public EdgeKludgingWrapperRunningAverageFactory(RunningAverageFactory f, int maxReports) {
        this.factory = f;
        this.maxReports = maxReports;
    }
    
    public RunningAverage create(double start) {
        return new EdgeKludgingWrapperRunningAverage(factory.create(start), maxReports);
    }

    public RunningAverage create(DataInputStream dis) throws IOException {
        return new EdgeKludgingWrapperRunningAverage(factory.create(dis), maxReports);
    }

    public RunningAverage create(FieldSet set) throws EstimatorFormatException {
        return new EdgeKludgingWrapperRunningAverage(factory.create(set), maxReports);
    }

}
