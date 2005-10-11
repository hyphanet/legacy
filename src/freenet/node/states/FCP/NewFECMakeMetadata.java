package freenet.node.states.FCP;

import java.io.InputStream;

import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.message.client.Failed;
import freenet.message.client.MadeMetadata;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.FECMakeMetadata;
import freenet.message.client.FEC.SegmentHeader;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.io.ReadInputStream;

public class NewFECMakeMetadata extends NewClientRequest {

	public NewFECMakeMetadata(long id, PeerHandler source) {
		super(id, source);
	}

	public final String getName() {
		return "New FEC Segment SplitFile";
	}

	public State received(Node n, MessageObject mo) throws BadStateException {
		if (!(mo instanceof FECMakeMetadata))
			throw new BadStateException("expecting FECMakeMetadata");

		FECMakeMetadata msg = ((FECMakeMetadata) mo);

		String mimeType = msg.getMimeType();
		String description = msg.getDescription();
		String checksum = msg.getChecksum();

		int nSegments = -1;

		SegmentHeader[] headers = null;
		BlockMap[] maps = null;

		BucketFactory bf = Node.fecTools.getBucketFactory();

		Bucket sfMeta = null;

		InputStream in = null;
		try {
			in = msg.getDataStream();

			// Read SegmentHeader, BlockMap pairs off of the
			// metadata stream.
			ReadInputStream rin = new ReadInputStream(in);
			int i = 0;
			while ((i < nSegments) || (nSegments == -1)) {

				SegmentHeader header = new SegmentHeader(id, rin);
				BlockMap map = new BlockMap(id, rin);
				if (nSegments == -1) {
					nSegments = header.getSegments();
					headers = new SegmentHeader[nSegments];
					maps = new BlockMap[nSegments];
				}
				headers[i] = header;
				maps[i] = map;

				i++;
			}

			rin.close();
			in.close();

			// Allocate a more than reasonable amount for the metadata.
			sfMeta = bf.makeBucket(550000);

			Node.fecTools.makeSplitFile(
				headers,
				maps,
				description,
				mimeType,
				checksum,
				sfMeta);

			// Send success message
			sendMessage(new MadeMetadata(id, sfMeta.size()));

			final Bucket[] data = new Bucket[1];
			data[0] = sfMeta;

			// Send data chunks
			// REDFLAG: Requires correct Bucket.size(),
			//          which doesn't work in FSBucketFactory.
			NewFECEncodeSegment.sendDataChunks(
				source,
				id,
				data,
				sfMeta.size(),
				16384);
		} catch (Exception e) {
			// REDFLAG: remove
			e.printStackTrace();
			sendMessage(new Failed(id, e.getMessage()));

		} finally {
			if (in != null) {
				try {
					in.close();
				} catch (Exception e) {
				}
			}
			if (sfMeta != null) {
				try {
					bf.freeBucket(sfMeta);
				} catch (Exception e) {
				}
			}
		}

		return null;
	}
}
