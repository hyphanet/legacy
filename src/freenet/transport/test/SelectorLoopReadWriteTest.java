/*
 * Created on Feb 16, 2004
 *
 */
package freenet.transport.test;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.util.Random;

import junit.framework.TestCase;
import freenet.Address;
import freenet.ConnectFailedException;
import freenet.transport.AsyncTCPCommunicationManager;
import freenet.transport.NIOCallback;
import freenet.transport.NIOReader;
import freenet.transport.NIOWriter;
import freenet.transport.TCP;
import freenet.transport.tcpConnection;
/**
 * @author Iakin
 * 
 */
public class SelectorLoopReadWriteTest extends TestCase {
	/**
	 * @author Iakin
	 *
	 */
	public static class SimpleNIOReader implements NIOReader {
		boolean registered = false;
		boolean unregistered = false;
		boolean queueClosed = false;
		boolean closed = false;
		final ByteBuffer b;
		byte[] recieved;
		SimpleNIOReader(int bufferSize,boolean useDirectBuffer){
			if(useDirectBuffer)
				b = ByteBuffer.allocateDirect(bufferSize);
			else
				b = ByteBuffer.allocate(bufferSize);
		}
		public int process(ByteBuffer b) {
			int oldLength = (recieved == null)?0:recieved.length;
			byte[] oldbuffer = recieved;
			byte[] a = b.array();
			recieved = new byte[oldLength+a.length];
			if(oldLength >0)
				System.arraycopy(oldbuffer,0,recieved,0,oldbuffer.length);
			System.arraycopy(a,0,recieved,oldLength,a.length);
			return 1;
		}
		public ByteBuffer getBuf() {
			return b;
		}
		public void closed() {
			assertTrue(!closed);
			closed = true;
		}

		public void queuedClose() {
			assertTrue(!queueClosed);
			queueClosed = true;
		}

		public void registered() {
			assertTrue(!registered);
			registered = true;
		}
		public void unregistered() {
			assertTrue(!unregistered);
			unregistered = true;
		}
		public boolean shouldThrottle() {
			return false;
		}
		public boolean countAsThrottled() {
			return false;
		}
	}
	/**
	 * @author Iakin
	 */
	public class SimpleNIOWriter implements NIOWriter {
		boolean registered = false;
		boolean unregistered = false;
		boolean queueClosed = false;
		boolean closed = false;
		int partSizeWritten = 0;
		int completeSizeDone = 0;
		boolean done = false;
		boolean status;

		public void jobDone(int size, boolean status) {
			assertTrue(!done);
			done = true;
			this.status =status;
			completeSizeDone = size;
		}
		public synchronized  void jobPartDone(int size) {
			partSizeWritten += size;
		}

		public void closed() {
			assertTrue(!closed);
			closed = true;
		}

		public void queuedClose() {
			assertTrue(!queueClosed);
			queueClosed = true;
		}
		public void registered() {
			assertTrue(!registered);
			registered = true;
		}
		public void unregistered() {
			assertTrue(!unregistered);
			unregistered = true;
		}
		public boolean shouldThrottle() {
			return false;
		}
		public boolean countAsThrottled() {
			return false;
		}
	}
	public static void main(String[] args) {
		junit.textui.TestRunner.run(SelectorLoopReadWriteTest.class);
	}
	static SimpleConnectionRunner FNPConnRunner;
	static SimpleConnectionThrottler throttler;
	static Address addr;
	static boolean initialized = false;
	static SelectorLoopTestHarness harness;
	static Random random = new Random();
	
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
	public SelectorLoopReadWriteTest(String name) {
		super(name);
	}

	public void testReadWrite() throws InterruptedException {
		//doRWTest(2000,1,2000,false,true,true);
		//doRWTest(2000,1,2000,false,false,true);
		doRWTest2(20000,1,true);
		doRWTest2(20000,1,false);
		
		 
		
	}
	/*
	private void doRWTest(int bytesToTransfer,int chunks,int receiveBufferSize,boolean useDirectRecieveBuffer,boolean sendOnOutboundConn) throws InterruptedException {
		tcpConnection outboundConn=null;
		tcpConnection inboundConn=null;
		FNPConnRunner.conn = null;
		throttler.setShouldReject(false);
		
		//Establish an outbound connection to the listener
		try {
			outboundConn = (tcpConnection)addr.connect(false);
		} catch (ConnectFailedException e) {
			assertTrue("Failed to connect", false);
		}
		Thread.sleep(1000);
		
		//Pick up the resutling inbound connection..
		inboundConn = (tcpConnection)FNPConnRunner.conn;
		assertNotNull("Did not get an inbound connection when creating an outbound one", inboundConn);
		
		//In what direction have we been asked to send the data
		tcpConnection sendConn;
		tcpConnection recieveConn;
		if(sendOnOutboundConn){
			sendConn = outboundConn;
			recieveConn = inboundConn;
		}else{
			sendConn = inboundConn;
			recieveConn = outboundConn;
		}
		
		//Create and register a network reader (if so wanted)
		SimpleNIOReader r = null;
		r = new SimpleNIOReader(receiveBufferSize,useDirectRecieveBuffer);
		register(recieveConn,r,tcpConnection.getRSL());

		//Create and register a network writer		
		SimpleNIOWriter w = new SimpleNIOWriter();
		AsyncTCPWriteChannel wsl = tcpConnection.getWSL();
		SelectableChannel sc = register(sendConn,w,wsl);
		
		//Generate some random data to send
		byte[] data = new byte[bytesToTransfer];
		random.nextBytes(data);
		
		//Send the data
		try {
			for(int i =0;i<chunks;i++){
				wsl.send(data,i*(bytesToTransfer/chunks),(bytesToTransfer/chunks),sc,w,0);
			}
		} catch (IOException e2) {
			assertTrue("Unexpected exception",false);
		}

		Thread.sleep(1000);		//Wait for the send to complete
		assertTrue(w.done);		//Ensure that the write has completed
		
		assertTrue(w.status);
		assertEquals(w.completeSizeDone,bytesToTransfer);

		
		assertNotNull(r.recieved);
		assertEquals(bytesToTransfer,r.recieved.length);
		assertEquals(r.recieved,data);
	}
	*/
	
	private void doRWTest2(int bytesToTransfer,int chunks,boolean sendOnOutboundConn) throws InterruptedException {
		tcpConnection outboundConn=null;
		tcpConnection inboundConn=null;
		FNPConnRunner.conn = null;
		throttler.setShouldReject(false);
		
		//Establish an outbound connection to the listener
		try {
			outboundConn = (tcpConnection)addr.connect(false);
		} catch (ConnectFailedException e) {
			assertTrue("Failed to connect", false);
		}
		Thread.sleep(1000);
		
		//Pick up the resutling inbound connection..
		inboundConn = (tcpConnection)FNPConnRunner.conn;
		assertNotNull("Did not get an inbound connection when creating an outbound one", inboundConn);
		
		//In what direction have we been asked to send the data
		tcpConnection sendConn;
		tcpConnection recieveConn;
		if(sendOnOutboundConn){
			sendConn = outboundConn;
			recieveConn = inboundConn;
		}else{
			sendConn = inboundConn;
			recieveConn = outboundConn;
		}

		//Create and register a network writer		
		//SimpleNIOWriter w = new SimpleNIOWriter();
		//WriteSelectorLoop wsl = tcpConnection.getWSL();
		//SelectableChannel sc = register(sendConn,w,wsl);
		
		//Generate some random data to send
		byte[] dataToSend = new byte[bytesToTransfer];
		random.nextBytes(dataToSend);
		dataToSend[0]=0;
		dataToSend[1]=1;
		dataToSend[2]=2;
		dataToSend[3]=3;
		//tcpConnection.startSelectorLoops(new VoidLogger(),new VoidDiagnostics(),false,false);
		//Send the data
		OutputStream out = sendConn.getOut();
		try {
			for(int i =0;i<chunks;i++){
				out.write(dataToSend,i*(bytesToTransfer/chunks),(bytesToTransfer/chunks));
			}
		} catch (IOException e2) {
			assertTrue("Unexpected exception",false);
		}
		try {
			out.flush(); //Must be done or else nothing will be sent. 
		} catch (IOException e3) {
			assertTrue("Unexpected exception",false);
		}

		Thread.sleep(1000);		//Wait for the send to complete

		int[] dataRecieved = new int[bytesToTransfer];
		InputStream i = recieveConn.getIn();
		int bytesRead = 0;
		try {
			int r = i.read();
			dataRecieved[bytesRead] = r;
			bytesRead++;
			if(bytesRead>bytesToTransfer)
				assertTrue("Recieved more data than was sent",false);
		} catch (IOException e1) {
			assertTrue("Unexpected exception",false);
		}

		
		
	}
	
	private SelectableChannel register(tcpConnection conn,NIOCallback cb,AsyncTCPCommunicationManager loop){
		SelectableChannel sc=null;
		try {
			sc = conn.getSocket().getChannel();
		} catch (IOException e1) {
			assertTrue("Unexpected exception",false);
		}
		loop.register(sc,cb);
		return sc;
	}
}
