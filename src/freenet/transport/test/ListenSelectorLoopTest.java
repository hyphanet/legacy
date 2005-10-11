/*
 * Created on Feb 16, 2004
 *
 */
package freenet.transport.test;
import junit.framework.TestCase;
import freenet.Address;
import freenet.ConnectFailedException;
import freenet.Connection;
import freenet.transport.TCP;
/**
 * @author Iakin
 * 
 */
public class ListenSelectorLoopTest extends TestCase {
	public static void main(String[] args) {
		junit.textui.TestRunner.run(ListenSelectorLoopTest.class);
	}
	static SimpleConnectionRunner FNPConnRunner;
	static SimpleConnectionThrottler throttler;
	static Address addr;
	static boolean initialized = false;
	static SelectorLoopTestHarness harness;
	
	protected void setUp() throws Exception {
		if (!initialized) {
			FNPConnRunner = new SimpleConnectionRunner();
			throttler = new SimpleConnectionThrottler();
			addr = new TCP(100, true).getAddress("localhost:7777");
			harness = new SelectorLoopTestHarness(addr,throttler,FNPConnRunner,new SimpleThreadFactory());
		}
		initialized = true;
	}
	protected void tearDown() throws Exception {
	}
	public ListenSelectorLoopTest(String name) {
		super(name);
	}

	public void testEtablishConnection() throws InterruptedException {
		//First try rejecting
		Connection outboundConn;
		Connection inboundConn;
		FNPConnRunner.conn = null;
		throttler.setShouldReject(true);
		try {
			outboundConn = addr.connect(false);
		} catch (ConnectFailedException e) {
			assertTrue("Failed to connect", false);
		}
		Thread.sleep(1000);
		inboundConn = FNPConnRunner.conn;
		assertNull("Did get an inbound connection even though it should have been rejected", inboundConn);
		
		//Then try allowing..
		FNPConnRunner.conn = null;
		throttler.setShouldReject(false);
		try {
			outboundConn = addr.connect(false);
		} catch (ConnectFailedException e) {
			assertTrue("Failed to connect", false);
		}
		Thread.sleep(1000);
		inboundConn = FNPConnRunner.conn;
		assertNotNull("Did not get an inbound connection when creating an outbound one", inboundConn);
	}
}
