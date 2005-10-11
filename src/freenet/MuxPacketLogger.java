/*
 * Created on Dec 29, 2003
 *
 * To change the template for this generated file go to
 * Window - Preferences - Java - Code Generation - Code and Comments
 */
package freenet;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;

import freenet.support.Logger;

/**
 * Logs low-level packets to a file. Should probably be converted to
 * an interface at some point.
 * @author amphibian
 */
public class MuxPacketLogger {

	final PrintWriter logStream;
	/**
	 * Create a new MuxPacketLogger that should log to the file
	 * named <identity with spaces converted to dashes>-<type>.txt
	 */
	public MuxPacketLogger(String type, PeerHandler ph) {
		String filename = ph.id.toString();
		StringBuffer buf = new StringBuffer();
		filename = filename.replace(' ','-');
		buf.append(filename);
		buf.append('-');
		buf.append(type);
		buf.append(".txt");
		filename = buf.toString();
		PrintWriter p = null;
		try {
			OutputStream os = new FileOutputStream(filename);
			p = new PrintWriter(os);
		} catch (IOException e) {
			Core.logger.log(this, "Could not open packet log file: "+filename+": "+
				e, e, Logger.NORMAL);
			p = null;
		}
		logStream = p;
	}
	/**
	 * @param message
	 */
	public void log(TrailerChunkPacketMessage message) {
		if(logStream == null) return;
		String logItem = "ID: "+message.id+", Length: "+message.length+
			", Offset: "+message.keyOffset;
		logStream.println(logItem);
		// Don't include objid, for easy comparison
		// But log it in core, for debugging after found an interesting packet
		Core.logger.log(this, logItem+": "+message, Logger.DEBUG);
		logStream.flush();
	}
}
