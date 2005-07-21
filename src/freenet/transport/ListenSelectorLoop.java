/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.transport;
//hope this belongs here

import java.io.IOException;
import java.nio.channels.SelectionKey;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.Set;

import freenet.NIOListener;
import freenet.crypt.RandomSourcePool;
import freenet.diagnostics.ExternalContinuous;
import freenet.support.Logger;

/**
 * this selector registers ServerSocketChannels, the interfaces
 * listen on it.
 */
public final class ListenSelectorLoop extends AbstractSelectorLoop
                           implements freenet.support.Checkpointed {

    //again, this is arbitrary.
    private static final int MAX_NEW_CONNECTIONS = 20;

   // private HashMap readySockets;
    
    public ListenSelectorLoop(Logger logger, ExternalContinuous callback, RandomSourcePool pool) 
    	throws IOException{
        super(logger, callback, pool);
        //readySockets=new HashMap(MAX_NEW_CONNECTIONS);
    }

    protected final void beforeSelect() {
        //readySockets.clear();
    }

    protected final int myKeyOps() {
        return SelectionKey.OP_ACCEPT;
    }

    protected final boolean inspectChannels() {
    	//deprecate this, just return false; we're not going through the map anymore --zab
        //we're good here, don't need fixkeys
        //currentSet = sel.selectedKeys();
     /*   Iterator i = sel.selectedKeys().iterator();
        boolean noneWorking = true;
        try {
        while (i.hasNext()) {
            SelectionKey current = (SelectionKey)i.next();
            i.remove();
            ServerSocketChannel sc = (ServerSocketChannel)current.channel();

            //if this throws classcast exception it was misused.
            //I'll do the specific catching later
            NIOListener listener = (NIOListener) current.attachment();

            //this may not work - I'm guessing that if accept()
            //returns null, the channel is screwed.
            SocketChannel chan=null;
            //try{
            chan = sc.accept();  //leak not here --zab
            /*}/catch (IOException e) {   
                if (e.getMessage().indexOf("Too many open files")==-1) throw e;
                freenet.logger.log(this,"too many incoming connections, disabling listener for a while"+
                            " size of close queue " + closeQueueLength() +
                            " size of socketMap "+tcpConnection.socketConnectionMap.size(),
                        freenet.support.Logger.NORMAL);  
                sc.keyFor(sel).cancel();
                delayedWaiters.add(new DelayedChannelAttachmentPair(sc,listener, 5*1000));
                //FIXME: totally arbitrary, lets try 5 seconds.
                
            }

            if (chan == null) {
                sc.close();
                continue;
            }else {
                readySockets.put(chan,listener);
                noneWorking=false;
            }
        }
        }catch(Throwable t) {t.printStackTrace();}*/
        return false;

    }

    int oomSleep = 1000;

    //we're ok here
    protected final void fixKeys() {}

    protected final boolean processConnections(Set currentSet) {
        boolean success = true;
        oomSleep = 1000;
        try {
            Iterator i = currentSet.iterator();
            while (i.hasNext()) {
                try {
					SelectionKey curKey = (SelectionKey)i.next();
					//i.remove(); //yes I think its a good idea // reenable iff switch back to selectedKeys
					ServerSocketChannel sc = (ServerSocketChannel)curKey.channel();
					SocketChannel chan = null;
					NIOListener listener = (NIOListener)curKey.attachment();
					//drain the channel
					do {
						chan = sc.accept();
						if (chan!=null)
							listener.accept(chan.socket());
					}while(chan!=null);
                } catch (OutOfMemoryError e) {
                    System.gc();
                    System.runFinalization();
                    Thread.yield();
                    System.gc();
                    System.runFinalization();
                    Thread.sleep(oomSleep);
                    System.gc();
                    System.runFinalization();
                    oomSleep *= 2;
                    try {
                        String s = "Attempted to recover from OutOfMemoryError "+
							e.toString();
                        logger.log(this, s, 
                                                freenet.support.Logger.ERROR);
                        System.err.println(s);
                    } catch (Throwable t) {}
                }
            }
        } catch (Throwable t) {
			String s = "Caught a "+t+
				", LSL.processConnections failing";
            System.err.println(s);
            t.printStackTrace();
			logger.log(this, s, t, Logger.ERROR);
            success=false;
        }
		
        return success;
		
    }
	
    /**
     * the run method.  This is the place to add more init stuff
     * NOTE: perhaps we want to catch exceptions here.  But for now
     * I like to print everything on stderr.
     */
    public final void run() {
        loop();
    }

    //TODO: need to decide what happens when we close the selector this way.
    public final void close(){}

    private int size;

    public void checkpoint() {
        int s = sel.keys().size();
        if(size > s)
            logger.log(this, "Lost " + (size - s) + " key(s) from the listen selector.  You might as well restart your node, it's really screwed now.  Please report JVM & OS version to devl@freenetproject.org", freenet.support.Logger.ERROR);
        size = s;
    }

    public String getCheckpointName() {
        return "Listen selector key monitor";
    }

    public long nextCheckpoint() {
        return System.currentTimeMillis() + 60000;
    }

}
