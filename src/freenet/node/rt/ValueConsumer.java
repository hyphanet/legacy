/*
 * Created on Feb 11, 2004
 */
package freenet.node.rt;

/**
 * @author Iakin
 * An interface for something that can recieve double or long values and use them for _something_ 
 */
public interface ValueConsumer {
	public void report(double d);
	public void report(long d);
}
