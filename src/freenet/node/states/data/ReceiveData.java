package freenet.node.states.data;

import java.io.IOException;

import freenet.Core;
import freenet.MessageObject;
import freenet.Presentation;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.ds.KeyCollisionException;
import freenet.node.ds.KeyInputStream;
import freenet.node.ds.KeyOutputStream;
import freenet.support.Logger;
import freenet.support.io.DataNotValidIOException;
import freenet.support.io.VerifyingInputStream;

/**
 * Reads data from a stream and feeds it to a data object.
 * 
 * @author Oskar
 */
public class ReceiveData extends DataState {

	private VerifyingInputStream data;
	private KeyOutputStream out;

	private final long length;

	private volatile int result = -1;
	private boolean silent = false;

	/**
	 * @param id
	 *            the id this data chain will run under
	 * @param parent
	 *            the id of the chain the DataStateReply will go to
	 * @param data
	 *            stream containing the key data
	 * @param length
	 *            number of bytes to receive
	 * @param out
	 *            stream to cache the data in the store
	 */
	public ReceiveData(
		long id,
		long parent,
		VerifyingInputStream data,
		KeyOutputStream out,
		long length) {
		super(id, parent);
		this.length = length;
		this.out = out;
		this.data = data;
		if (logDEBUG)
			Core.logger.log(
				this,
				"new ReceiveData("
					+ Long.toHexString(id)
					+ ","
					+ Long.toHexString(parent)
					+ ","
					+ out
					+ ","
					+ data
					+ ","
					+ length,
				Logger.DEBUG);
	}

	public final String getName() {
		return "Receiving Data";
	}

	public long length() {
		return length;
	}

	public final int result() {
		return result;
	}

	public final void setSilent() {
	    if(logDEBUG)
	        Core.logger.log(this, "Setting silent: "+this,
	                Logger.DEBUG);
	    silent = true;
	}
	
	public final void cancel() {
		silent = true;
		result = Presentation.CB_CANCELLED;
		if (out != null)
			out.rollback();
		//out.fail(result);
	}

	public final void commit() throws IOException, KeyCollisionException {
		if (Core.logger.shouldLog(Logger.DEBUG, this))
			Core.logger.log(this, "Committing " + this, Logger.DEBUG);
		if (out != null)
			out.commit();
	}

	public final KeyInputStream getKeyInputStream() throws IOException {
		return out.getKeyInputStream();
	}

	/**
	 * If the state is lost, we're too overworked to try to eat the data
	 * anyway.
	 */
	public final void lost(Node n) {
		try {
			out.fail(Presentation.CB_CANCELLED);
			out.close();
		} catch (IOException e) {
			Core.logger.log(
				this,
				"I/O error closing KeyOutputStream",
				e,
				Logger.ERROR);
		}
		try {
			data.close();
		} catch (IOException e) {
			Core.logger.log(
				this,
				"I/O error closing data receive stream",
				e,
				Logger.MINOR);
		}
	}

	public State received(Node n, MessageObject mo) throws BadStateException {
		if (!(mo instanceof DataStateInitiator))
			throw new BadStateException("expecting DataStateInitiator");

		// if there is an IOException, this says
		// whether it was while writing to the store
		boolean inWrite = false;

		byte[] buffer = new byte[Core.blockSize];
		long moved = 0;

		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);

		try {
			while (moved < length) {
				inWrite = false;
				int m =
					data.read(
						buffer,
						0,
						(int) Math.min(length - moved, buffer.length));
				if (logDEBUG)
					Core.logger.log(
						this,
						"Read " + m + " bytes (" + this +")",
						Logger.DEBUG);
				if (m < 0) {
					throw new IOException(
						"Could not read all the expected "
							+ "data, read "
							+ moved
							+ " of "
							+ length
							+ " for "
							+ Long.toHexString(id)
							+ ":"
							+ Long.toHexString(parent));
				}
				moved += m;
				inWrite = true;
				if (logDEBUG)
					Core.logger.log(
						this,
						"Moved "
							+ moved
							+ " of "
							+ length
							+ " bytes for "
							+ Long.toHexString(parent)
							+ ":"
							+ Long.toHexString(id)
							+ " ("
							+ data
							+ ")",
						Logger.DEBUG);

				if (result != -1)
					throw new CancelledIOException();
				out.write(buffer, 0, m);
				if (logDEBUG)
					Core.logger.log(
						this,
						"Written " + m + " bytes (" + this +")",
						Logger.DEBUG);
			}
			if (logDEBUG)
				Core.logger.log(
					this,
					"Closing, moved "
						+ moved
						+ " of "
						+ length
						+ " bytes for "
						+ Long.toHexString(parent)
						+ ":"
						+ Long.toHexString(id)
						+ " ("
						+ data
						+ ")",
					Logger.DEBUG);
			out.close();
			result = Presentation.CB_OK;
		} catch (DataNotValidIOException e) {
			result = e.getCode();
			Core.logger.log(
				this,
				"Got DNV: "
					+ Presentation.getCBdescription(result)
					+ " for "
					+ this
					+ " from "
					+ data
					+ " to "
					+ out,
				e,
				(result == Presentation.CB_ABORTED
					|| result == Presentation.CB_RESTARTED)
					? Logger.MINOR
					: Logger.NORMAL);
			result = Presentation.fixReceivedFailureCode(result);
		} catch (CancelledIOException e) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"CancelledIOException moving data for " + this,
					e,
					Logger.DEBUG);
		} // already set result
		catch (IOException e) {

			result =
				(inWrite
					? Presentation.CB_CACHE_FAILED
					: Presentation.CB_RECV_CONN_DIED);
			Core.logger.log(
				this,
				"I/O error while moving data: "
					+ Presentation.getCBname(result)
					+ " for "
					+ this,
				e,
				inWrite ? Logger.ERROR : Logger.MINOR);
			//                         e, Logger.ERROR);
		} catch (Throwable t) {
			Core.logger.log(
				this,
				"Uncaught Throwable receiving data: "
					+ t
					+ " for "
					+ this
					+ ", result="
					+ result
					+ ", moved="
					+ moved
					+ ", length="
					+ length,
				t,
				Logger.ERROR);
			result = Presentation.CB_CACHE_FAILED; // umm, kinda
		} finally {

			Core.diagnostics.occurrenceBinomial(
				"receivedData",
				1,
				result == Presentation.CB_OK ? 1 : 0);

			if (result != Presentation.CB_OK) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Failing "
							+ this
							+ ", result="
							+ Integer.toHexString(result)
							+ ", moved="
							+ moved
							+ ", length="
							+ length,
						Logger.DEBUG);
				out.fail(result);
				//out.rollback();
				try {
					out.close();
				} catch (IOException e) {
					Core.logger.log(
						this,
						"I/O error closing KeyOutputStream for " + this,
						e,
						Logger.ERROR);
				}
			}

			// we could be exiting with an uncaught exception or something..
			if (result == -1)
				result = Presentation.CB_RECV_CONN_DIED;

			if (!silent)
				n.schedule(new DataReceived(this));
			else {
			    // Cleanup after silent receive
			    if(result == Presentation.CB_OK) {
			        // Success!
			        try {
			            out.commit();
			        } catch (KeyCollisionException e) {
			            Core.logger.log(this, "Successful silent ReceiveData got "+e,
			                    Logger.MINOR);
			        } catch (IOException e) {
			            Core.logger.log(this, "Successful silent ReceiveData got "+e,
			                    Logger.NORMAL);
			        }
			    } // otherwise will already have been released by fail/close/rollback
			}

			// eat the rest if necessary
			if (moved < length && inWrite) {
				DataState eatData = new EatData(id, data, length - moved);
				try {
					eatData.received(n, new DataStateInitiator(eatData));
					return null; // EatData closed the stream.
				} catch (Throwable e) {
				}
			}

			try {
				if (result == Presentation.CB_RESTARTED
					|| result == Presentation.CB_ABORTED)
					data.discontinue();
				else
					data.close();
			} catch (IOException e) {
				Core.logger.log(
					this,
					"I/O error closing data receive stream for " + this,
					e,
					Logger.NORMAL);
			}

			// Clear it so it can be finalized even if we aren't.
			data = null;

		}
		return null;
	}

	private class CancelledIOException extends IOException {
	}

}
