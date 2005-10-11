package freenet.node.states.request;

import freenet.CommunicationException;
import freenet.Core;
import freenet.MessageSendCallback;
import freenet.TrailerWriter;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Logger;

/**
 * Message Object for async send callback
 * 
 * @see RequestSendCallback
 */
public class SendFinished
	extends RequestObject
	implements MessageSendCallback {

	final long initTime;
	private long finishTime;
	private long mustHaveSucceededTime = -1;
	private Exception finishException;
	private boolean succeeded;
	final Node n;
	final String logMessage;
	private TrailerWriter tw;

	public void setTrailerWriter(TrailerWriter tw) {
		this.tw = tw;
	}

	public TrailerWriter getTrailerWriter() {
		return tw;
	}

	public SendFinished(Node n, long id, String logMessage) {
		super(id, true);
		initTime = System.currentTimeMillis();
		finishTime = -1;
		finishException = null;
		succeeded = false;
		this.n = n;
		this.logMessage = logMessage;
		if(Core.logger.shouldLog(Logger.DEBUG,this)) Core.logger.log(this, "Created " + this, Logger.DEBUG);
	}

	public String toString() {
		StringBuffer buf = new StringBuffer(super.toString());
		buf
			.append("@ ")
			.append(finishTime)
			.append(':')
			.append(initTime)
			.append(':')
			.append(succeeded)
			.append(':')
			.append(finishException)
			.append(':')
			.append(logMessage);
		return buf.toString();
	}

	public long startTime() {
		return initTime;
	}

	public long endTime() {
		return finishTime;
	}

	public boolean finished() {
		return finishTime != -1;
	}

	public boolean getSuccess() {
		return succeeded;
	}

	public Exception failCause() {
		return finishException;
	}

	public void succeeded() {
		finishTime = System.currentTimeMillis();
		succeeded = true;
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(this, toString() + " succeeded", Logger.DEBUG);
		if (mustHaveSucceededTime != -1
			&& finishTime - mustHaveSucceededTime > 10 * 1000)
			Core.logger.log(
				this,
					toString() + " succeeded "
					+ ((finishTime - mustHaveSucceededTime) / 1000)
					+ " seconds after its effect!",
				Logger.ERROR);
		n.schedule(this, true);
	}

	public void mustHaveSucceeded() {
		mustHaveSucceededTime = System.currentTimeMillis();
	}

	public void thrown(Exception e) {
		finishTime = System.currentTimeMillis();
		succeeded = false;
		finishException = e;
		if (e instanceof CommunicationException) {
			CommunicationException ce = (CommunicationException) e;
			Core.logger.log(
				this,
				"Failed to send to peer " + ce.peer + " (" + this +")",
				e,
				Logger.MINOR);
		} else {
			Core.logger.log(
				this,
				"Unexpected exception sending for " + this +": " + e,
				e == null ? new Exception("debug") : e,
				Logger.NORMAL);
		}
		if (mustHaveSucceededTime != -1
			&& finishTime - mustHaveSucceededTime > 10 * 1000)
			Core.logger.log(
				this,
				"SendFinished failed ("
					+ e
					+ ") "
					+ ((finishTime - mustHaveSucceededTime) / 1000)
					+ " seconds after its effect!",
				Logger.ERROR);
		n.schedule(this);
	}

	public final State getInitialState() {
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(
				this,
				"getInitialState() called on " + this,
				new Exception("debug"),
				Logger.DEBUG);
		return null;
	}
}
