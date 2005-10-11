/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.transport;


 public interface NIOWriter extends NIOCallback {
 
    /**
     * best to have it here
     * I am however beginning to doubt we need something that big;
     * pipestreams use just 1k...
     */
    public static final int BUF_SIZE=64*1024;
       
    /**
     * have the boolean just in case we need to close, etc.
     */
    public void jobDone(int size, boolean status);
	
	public void jobPartDone(int size);
 }
