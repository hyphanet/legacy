package freenet.node.states.request;

import freenet.*;
import freenet.node.*;
import freenet.node.states.data.*;
import freenet.node.ds.KeyCollisionException;
import freenet.message.*;
import freenet.support.Logger;
import java.io.IOException;

/**
 * We get into this state if we are handling a DataRequest and have
 * lost the ability to communicate the StoreData back to the originator,
 * but we still are receiving the data and want to get the StoreData
 * for ourselves.
 */
public class ReceivingReply extends DataPending {

	ReceivingReply(Pending ancestor) {
		super(ancestor);
		this.receivingData = ancestor.receivingData;
		this.storeData = ancestor.storeData;
		this.accepted = ancestor.accepted;
	}

	public final String getName() {
		return "Receiving Reply";
	}

	//=== message handling =====================================================

	// we just ignore most of these (declaring them keeps BSEs out of the log)
	// since if the receive fails we won't bother restarting

	// we'll wait for the DataReceived to go to RequestDone, it's easier

	public State receivedMessage(Node n, QueryRestarted qr) {
		return this;
	}

	public State receivedMessage(Node n, QueryRejected qr) {
		// Transfer will be aborted
		return this;
	}

	public State receivedMessage(Node n, DataNotFound dnf) {
		// technically, we might want to increase their contact probability
		// if they restarted correctly and then replied with this..
		routes.dataNotFound(hopsToLive);
		// Transfer will be aborted.
		return this;
	}

	public State receivedMessage(Node n, DataReply dr) {
		// the heck with restarting
		dr.drop(n);
		return this;
	}

	public State receivedMessage(Node n, Accepted a) {
		// who cares ;)
		return this;
	}

	public State receivedMessage(Node n, StoreData sd)
		throws BadStateException {
		super.receivedStoreData(n, sd);
		return this;
	}

	public State receivedMessage(Node n, DataRequest dr) {
		super.receivedRequest(n, dr);
		return this;
	}

	public State receivedMessage(Node n, DataReceived dr)
		throws StateException {
		if (receivingData != dr.source()) {
			throw new BadStateException("Not my DataReceived: " + dr);
		}
		dataReceived = dr;
		int cb = dr.getCB();
		endTransferTime = System.currentTimeMillis();
		switch (cb) {

			case Presentation.CB_OK :
				try {
					receivingData.commit(); // make the key available
				} catch (KeyCollisionException e) {
					Core.logger.log(
						this,
						"Abandoning after key collision: " + this,
						Logger.MINOR);
					// 		    logSuccess(n);
					break;
				} catch (IOException e) {
					Core.logger.log(
						this,
						"Cache failed on commit: " + this,
						e,
						Logger.ERROR);
					// 		    logSuccess(n); // it's not the node's fault...
					break;
				}
				Core.logger.log(
					this,
					"Data received successfully!: " + this,
					Logger.MINOR);
				routes.transferSucceeded(
					replyTime - routedTime,
					hopsToLive,
					receivingData.length(),
					endTransferTime - replyTime);
				// 		logSuccess(n);
				if (storeData == null) {
					NoStoreData nosd = new NoStoreData(this);
	                // Don't use Core.storeDataTime, they are not transferring, this is a request.
					long timeout = 2 * hopTimeHTL(hopsToLive, remoteQueueTimeout());
					n.schedule(timeout, nosd);
					Core.logger.log(
						this,
						"Returning AwaitingStoreData from " + this,
						Logger.DEBUG);
					return new AwaitingStoreData(this, nosd);
				} else {
					Core.logger.log(
						this,
						"Transitioning to AwaitingStoreData from " + this,
						Logger.DEBUG);
					throw new StateTransition(
						new AwaitingStoreData(this, null),
						storeData,
						true);
				}

			case Presentation.CB_CACHE_FAILED :
				Core.logger.log(
					this,
					"Cache failed while receiving data!: " + this,
					Logger.ERROR);
				break;

			case Presentation.CB_BAD_DATA :
				Core.logger.log(
					this,
					"Upstream node sent bad data!: " + this,
					Logger.NORMAL);
				routes.transferFailed(
					replyTime - routedTime,
					hopsToLive,
					receivingData.length(),
					endTransferTime - replyTime);
				break;

			case Presentation.CB_RESTARTED :
				// we don't want it that bad
				Core.logger.log(
					this,
					"Restart from " + getName() + ", dropping..: " + this,
					Logger.DEBUG);
				// Don't log either success or failure... this can only happen if the original requester loses it after all
				break;

			case Presentation.CB_RECV_CONN_DIED :
				Core.logger.log(
					this,
					"Receiving connection died in " + this,
					Logger.NORMAL);
				Core.diagnostics.occurrenceCounting(
					"recvConnDiedInTransfer",
					1);
				// Continue to default processing
			default :
				if (lastPeer != null) {
					long length = receivingData.length();
					length =
						Key.getTransmissionLength(
							length,
							Key.getPartSize(length));
					routes.transferFailed(
						replyTime - routedTime,
						hopsToLive,
						length,
						endTransferTime - replyTime);
				}
				routes.transferFailed(
					replyTime - routedTime,
					hopsToLive,
					receivingData.length(),
					endTransferTime - replyTime);
				// 		logFailedTransfer(n); // better safe than sorry...
				Core.logger.log(
					this,
					"Failed to receive data with CB "
						+ Presentation.getCBdescription(cb)
						+ ", on chain "
						+ Long.toHexString(id)
						+ " for "
						+ this,
					Logger.MINOR);
		}

		Core.logger.log(
			this,
			"Transitioning to RequestDone from " + this,
			Logger.DEBUG);
		terminateRouting(false, true, false);
		// routing will ignore if we succeeded already
		return new RequestDone(this);
	}

	public State receivedMessage(Node n, SendFinished sf) {
		// Too important - we are transferring, we don't care
		Core.logger.log(this, "Got " + sf + " in " + this, Logger.MINOR);
		return this;
	}
}
