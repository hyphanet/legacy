package freenet.node.states.FCP;

import java.io.IOException;
import java.io.InputStream;

import freenet.Core;
import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.message.client.BlocksDecoded;
import freenet.message.client.Failed;
import freenet.message.client.FEC.FECDecodeSegment;
import freenet.message.client.FEC.SegmentHeader;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.Logger;
import freenet.support.io.ReadInputStream;

public class NewFECDecodeSegment extends NewClientRequest {

	public NewFECDecodeSegment(long id, PeerHandler source) {
		super(id, source);
	}

	public final String getName() {
		return "New FEC Decode Segment";
	}

	public State received(Node n, MessageObject mo) throws BadStateException {
		if (!(mo instanceof FECDecodeSegment))
			throw new BadStateException("expecting FECDecodeSegment");

		FECDecodeSegment fdmo = (FECDecodeSegment) mo;

		int[] requestedList = fdmo.requestedList();
		int[] checkList = fdmo.checkList();
		int[] blockList = fdmo.blockList();

		if (checkList.length < 1) {
			throw new RuntimeException("Nothing to decode. No check blocks sent!");
		}

		BucketFactory bf = Node.fecTools.getBucketFactory();

		Bucket[] blocks = new Bucket[checkList.length + blockList.length];
		Bucket[] missing = null;
		Bucket[] tmp = null;

		InputStream in = null;
		try {
			in = fdmo.getDataStream();

			// Read SegmentHeader off of metadata stream
			//
			// Keep an explict ref to the ReadInputStream so
			// it doesn't get garbage collected and cause
			// in to be closed *before* we exit this scope.
			// REDFLAG: investigate, unnesc. paranoia?
			ReadInputStream rin = new ReadInputStream(in);

			// Hmmm... risky. Can we start reading data
			// in the wrong place if the client sends us
			// a messed up segment header? I don't want
			// to copy to a bucket if I don't have to though
			SegmentHeader header = new SegmentHeader(id, rin);

			long dataLen = header.getBlockSize() * blockList.length;
			long checkLen = header.getCheckBlockSize() * checkList.length;

			// Read the data blocks
			tmp =
				NewFECEncodeSegment.copyFully(
					in,
					dataLen,
					blockList.length,
					bf,
					false);
			System.arraycopy(tmp, 0, blocks, 0, tmp.length);
			tmp = null;

			// REDFLAG: hmmm creates a second DataInputStream. OK?
			// Read the check blocks
			tmp =
				NewFECEncodeSegment.copyFully(
					in,
					checkLen,
					checkList.length,
					bf,
					true);

			System.arraycopy(tmp, 0, blocks, blockList.length, tmp.length);
			tmp = null;

			// Decode
			missing =
				Node.fecTools.decodeSegment(
					header,
					blockList,
					checkList,
					requestedList,
					blocks);

			// Write success msg
			sendMessage(
				new BlocksDecoded(id, missing.length, header.getBlockSize()));

			// Because missing can contain both data and check blocks.
			long missingLen = 0;
			for (int i = 0; i < missing.length; i++) {
				missingLen += missing[i].size();
			}

			// Write data
			NewFECEncodeSegment.sendDataChunks(
				source,
				id,
				missing,
				missingLen,
				16384);

		} catch (Exception e) {
			// REDFLAG: remove
			e.printStackTrace();
			sendMessage(new Failed(id, e.getMessage()));
		} finally {
			
			try {
				BucketTools.freeBuckets(bf, blocks);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"WARNING: Cannot free blocks buckets",
					e,
					Logger.ERROR);
			}
			try {
				BucketTools.freeBuckets(bf, missing);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"WARNING: Cannot free missing buckets",
					e,
					Logger.ERROR);
			}
			try {
				BucketTools.freeBuckets(bf, tmp);
			} catch (IOException e) {
				Core.logger.log(
					this,
					"WARNING: Cannot free temp buckets",
					e,
					Logger.ERROR);
			}
			
		}

		// Our work is done, so stop the chain.
		return null;
	}
}
