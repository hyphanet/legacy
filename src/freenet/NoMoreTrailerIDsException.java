/*
 * Created on Dec 30, 2003
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet;

/**
 * Exception thrown when a connection is so busy that we can't find
 * a trailer ID to send a new trailing field on.
 * @author amphibian
 */
public class NoMoreTrailerIDsException extends Exception {
	public NoMoreTrailerIDsException() {
	    // Do nothing
	}
}
