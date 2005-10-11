/*
 * Created on Dec 13, 2003
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet.presentation;

import java.io.IOException;

import freenet.BaseConnectionHandler;
import freenet.MuxConnectionHandler;
import freenet.OpenConnectionManager;
import freenet.PeerPacketMessageParser;
import freenet.Ticker;
import freenet.node.Node;
import freenet.session.Link;
import freenet.transport.ThrottledAsyncTCPReadManager;
import freenet.transport.ThrottledAsyncTCPWriteManager;
import freenet.transport.tcpConnection;

/**
 * Presentation for multiplexing.
 * The same as FreenetProtocol, except that it has a different designator
 * number and it creates MuxConnectionHandler's.
 * @author amphibian
 */
public class MuxProtocol extends FreenetProtocol {

	public final static int DESIGNATOR = 0x0003;
	
	private final PeerPacketMessageParser pmp;
	
	public MuxProtocol() {
		pmp = PeerPacketMessageParser.create();
	}
	
	public int designatorNum() {
		return DESIGNATOR;
	}

	/* (non-Javadoc)
	 * @see freenet.Presentation#createConnectionHandler(freenet.OpenConnectionManager, freenet.session.Link, freenet.Ticker, int, int, boolean)
	 */
	public BaseConnectionHandler createConnectionHandler(
		OpenConnectionManager manager,
		Node n,
		Link l,
		Ticker ticker,
		int maxInvalid,
		int maxPadding,
		boolean outbound,ThrottledAsyncTCPReadManager rsl,ThrottledAsyncTCPWriteManager wsl)
		throws IOException {
		return new MuxConnectionHandler((tcpConnection)(l.getConnection()),
						l, this, pmp, manager, ticker, outbound,rsl,wsl);
	}
}
