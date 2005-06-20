package freenet.node.rt;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.Serializable;

import freenet.FieldSet;

public interface RunningAverageFactory extends Serializable {
	RunningAverage create(double start);
	RunningAverage create(DataInputStream dis) throws IOException;
	/**
	 * Create a RunningAverage from the network-serialized form
	 * @param set FieldSet from which to create the RunningAverage
	 * @return
	 */
	RunningAverage create(FieldSet set) throws EstimatorFormatException;
}
