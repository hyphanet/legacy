/*
 * Created on Feb 17, 2004
 */
package freenet.transport.test;

import java.io.IOException;

import freenet.Address;
import freenet.ListenException;
import freenet.interfaces.ConnectionRunner;
import freenet.interfaces.NIOInterface;
import freenet.interfaces.PublicNIOInterface;
import freenet.node.ConnectionThrottler;
import freenet.support.VoidLogger;
import freenet.thread.ThreadFactory;
import freenet.transport.ListenSelectorLoop;

/**
 * @author Iakin
 *
 */
public class SelectorLoopTestHarness {
	static ListenSelectorLoop interfaceLoop;
	static Thread interfaceLoopThread;
	static boolean initialized = false;
	SelectorLoopTestHarness(Address addr,ConnectionThrottler throttler,ConnectionRunner runner, ThreadFactory tf) throws ListenException{
		if (!initialized) {
			NIOInterface i = new PublicNIOInterface(addr.listenPart(true), throttler, tf,runner , null, "test");
			try {
				interfaceLoop = new ListenSelectorLoop(new VoidLogger(),null);
				interfaceLoopThread = new Thread(interfaceLoop, " interface thread");
				i.register(interfaceLoop);
				interfaceLoopThread.start();
			} catch (IOException e) {
				System.err.println("couldn't create interfaces");
				e.printStackTrace();
			}
			initialized = true;
		}else
			throw new RuntimeException("Already intialized. May not beintialized twice");
		
	}
}
