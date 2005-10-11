/*
 * Created on Nov 13, 2003
 */
package freenet.node.rt;

import java.io.IOException;

/**
 * @author amphibian
 */
class EstimatorFormatException extends IOException {

	boolean important = true;
	/**
	 * Create an EstimatorFormatException  
	 */
	public EstimatorFormatException() {
		super();
	}

	/**
	 * Create an EstimatorFormatException
	 * @param message detail message
	 */
	public EstimatorFormatException(String message, boolean important) {
		super(message);
		this.important = important;
	}
	
	public EstimatorFormatException(String message) {
		super(message);
	}
}
