package freenet.client.metadata;

import freenet.Core;
import freenet.FieldSet;
import freenet.client.FreenetURI;
import freenet.client.RequestProcess;
import freenet.client.SplitFileInsertProcess;
import freenet.client.SplitFileRequestProcess;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.FileBucket;
import freenet.support.Logger;

/*
 * This code is distributed under the GNU Public Licence (GPL) version 2. See
 * http://www.gnu.org/ for further details of the GPL.
 */

/**
 * Parse SplitFiles.
 * 
 * @author giannij
 */
public class SplitFile extends MetadataPart {

	public static final String name = "SplitFile";

	protected long size;
	protected int blockSize = -1;
	protected String[] blockURIs = new String[0];
	protected String[] checkBlockURIs = new String[0];
	protected String algoName = null;
	protected String obsoleteDecoder = null;

	// For use in Oskars metadata handling framework.
	public SplitFile() {
	}

	// Client code shouldn't use this.
	// Hook for SplitFileInsertManager to update a SplitFile once it
	// has the block CHKs.
	public void lateConstruct(
		long size,
		String[] blockURIs,
		String[] checkBlockURIs,
		String algoName) {
		this.size = size;
		this.blockSize = -1;
		this.blockURIs = blockURIs;
		this.checkBlockURIs = checkBlockURIs;
		this.algoName = algoName;
		this.obsoleteDecoder = null;
	}

	// REDFLAG: graph?
	public SplitFile(long size, String[] blockURIs) {
		this.size = size;
		this.blockURIs = blockURIs;
	}

	// REDFLAG: get rid of this when fproxy is cleaned up
	// REDFLAG: get rid of explicit blockSize
	public SplitFile(
		long size,
		int blockSize,
		String[] blockURIs,
		String[] checkBlockURIs,
		String algoName) {
		this.size = size;
		this.blockSize = blockSize;
		this.blockURIs = blockURIs;
		this.checkBlockURIs = checkBlockURIs;
		this.algoName = algoName;
	}

	public SplitFile(
		long size,
		String[] blockURIs,
		String[] checkBlockURIs,
		String algoName) {
		this.size = size;
		this.blockURIs = blockURIs;
		this.checkBlockURIs = checkBlockURIs;
		this.algoName = algoName;
	}

	public SplitFile(FieldSet fs, MetadataSettings ms)
		throws InvalidPartException {
		if (fs.get("Size") == null) {
			throw new InvalidPartException(name() + ": Requires Size.");
		}
		try {
			size = Long.parseLong(fs.getString("Size"), 16);
		} catch (NumberFormatException nfe) {
			throw new InvalidPartException(name() + ": Couldn't read Size.");
		}

		if (fs.get("BlockSize") != null) {
			try {
				blockSize = Integer.parseInt(fs.getString("BlockSize"), 16);
			} catch (NumberFormatException nfe) {
				Core.logger.log(this, "Couldn't parse BlockSize", Logger.MINOR);
			}
		} else {
			Core.logger.log(this, "BlockSize not specified.", Logger.MINOR);
		}

		algoName = fs.getString("AlgoName");

		// Detect the old now unsupported format so
		// we can display an appropriate warning message.
		FieldSet decoderParams = fs.getSet("decoder");
		if (decoderParams != null) {
			obsoleteDecoder = decoderParams.getString("name");
		}

		int blockCount = 0;
		if (fs.get("BlockCount") == null) {
			throw new InvalidPartException(name() + ": Requires BlockCount.");
		}
		try {
			blockCount = Integer.parseInt(fs.getString("BlockCount"), 16);
		} catch (NumberFormatException nfe) {
			throw new InvalidPartException(
				name() + ": Couldn't read BlockCount.");
		}

		// REDFLAG: graph

		// Start at 1 not 0.

		FieldSet blocks = fs.getSet("Block");
		if (blocks != null) {

			blockURIs = new String[blockCount];
			int i;
			for (i = 0; i < blockURIs.length; i++) {
				blockURIs[i] = blocks.getString(Integer.toString(i + 1, 16));
				if (blockURIs[i] == null) {
					throw new InvalidPartException(
						name()
							+ ": Couldn't read Block."
							+ Integer.toString(i + 1, 16)
							+ ".");
				}
			}
		} else {
			throw new InvalidPartException(name() + ": Couldn't read Blocks.");
		}

		// Handle optional check block data.
		int checkBlockCount = 0;
		if (fs.get("CheckBlockCount") != null) {
			try {
				checkBlockCount =
					Integer.parseInt(fs.getString("CheckBlockCount"), 16);
			} catch (NumberFormatException nfe) {
				throw new InvalidPartException(
					name() + ": Couldn't read CheckBlockCount.");
			}

			FieldSet checkblocks = fs.getSet("CheckBlock");
			if (checkblocks != null) {
				checkBlockURIs = new String[checkBlockCount];
				for (int i = 0; i < checkBlockURIs.length; i++) {
					checkBlockURIs[i] =
						checkblocks.getString(Integer.toString(i + 1, 16));
					if (checkBlockURIs[i] == null) {
						throw new InvalidPartException(
							name()
								+ ": Couldn't read CheckBlock."
								+ Integer.toString(i + 1, 16)
								+ ".");
					}
				}
			}
		}

	}

	public void addTo(FieldSet fs) {
		FieldSet me = new FieldSet();
		me.put("Size", Long.toString(size, 16));
		if (blockSize != -1) {
			me.put("BlockSize", Integer.toString(blockSize, 16));
		}
		me.put("BlockCount", Integer.toString(blockURIs.length, 16));
		// REDFLAG: graph
		for (int i = 0; i < blockURIs.length; i++) {
			// start at 1
			me.put("Block." + Integer.toString(i + 1, 16), blockURIs[i]);
		}
		// handle optional check block data.
		if (checkBlockURIs.length > 0) {
			me.put(
				"CheckBlockCount",
				Integer.toString(checkBlockURIs.length, 16));
			// REDFLAG: graph
			for (int i = 0; i < checkBlockURIs.length; i++) {
				// start at 1
				me.put(
					"CheckBlock." + Integer.toString(i + 1, 16),
					checkBlockURIs[i]);
			}
		}

		if (algoName != null) {
			me.put("AlgoName", algoName);
		}

		fs.put(name(), me);
	}

	public long getSize() {
		return size;
	}
	public int getBlockCount() {
		return blockURIs.length;
	}
	public int getCheckBlockCount() {
		return checkBlockURIs.length;
	}

	// REDFLAG: Think about this some more.
	/**
	 * Returns the block size in bytes or -1 if this SplitFile doesn't have a
	 * fixed BlockSize.
	 */
	public int getBlockSize() {
		return blockSize;
	}

	public String getFECAlgorithm() {
		return algoName;
	}

	public String getObsoleteDecoder() {
		return obsoleteDecoder;
	}

	// non-const! copy?
	public String[] getBlockURIs() {
		return blockURIs;
	}
	public String[] getCheckBlockURIs() {
		return checkBlockURIs;
	}

	public String name() {
		return name;
	}

	public boolean isControlPart() {
		return true;
	}

	public RequestProcess getGetProcess(
		FreenetURI furi,
		int htl,
		Bucket data,
		BucketFactory ctBuckets,
		int recursionLevel,
		MetadataSettings ms) {

		//  	System.err.println("SplitFile.getGetProcess -- called");
		//          System.err.println("handleSplitFiles : " +
		// ms.getHandleSplitFiles());
		//          System.err.println("htl : " + ms.getSplitFileHtl());
		//          System.err.println("retryHtlIncrement: " +
		// ms.getSplitFileRetryHtlIncrement());
		//          System.err.println("retries : " + ms.getSplitFileRetries());
		//          System.err.println("threads : " + ms.getSplitFileThreads());
		//          System.err.println("clientFactory : " + ms.getClientFactory());

		if (!ms.getHandleSplitFiles()) {
			//System.err.println("Bailing out. ms.getHandleSplitFiles() ==
			// false");
			return null;
		}

		return new SplitFileRequestProcess(this, data, ctBuckets, ms);
	}

	public RequestProcess getPutProcess(
		FreenetURI furi,
		int htl,
		String cipher,
		Metadata metadata,
		MetadataSettings ms,
		Bucket data,
		BucketFactory ctBuckets,
		int recursionLevel,
		boolean descend) {

		if (!descend) {
			return null;
		}

		if (!(data instanceof FileBucket)) {
			// Underwhelming, but at least we fail early with a
			// message that explains what is going on.
			throw new IllegalArgumentException("SplitFileInsertProcess can only insert from a FileBucket!");
			// FIXME - WTF?
		}
		// REDFLAG: not sure I have this right.
		// This will only insert blocks. We don't care about furi or htl.
		// The blockHtl is in ms.
		return new SplitFileInsertProcess(
			this,
			cipher,
			(FileBucket) data,
			ctBuckets,
			ms);
	}

	public String toString() {
		StringBuffer value =
			new StringBuffer(
				"SplitFile [length="
					+ Long.toString(size, 16)
					+ "] [ blocks="
					+ Integer.toString(blockURIs.length, 16)
					+ "] [ blockSize="
					+ Integer.toString(blockSize, 16)
					+ "] [ algoName="
					+ algoName
					+ "] [ checkBlocks = "
					+ Integer.toString(checkBlockURIs.length, 16)
					+ "]\n");

		for (int i = 0; i < blockURIs.length; i++) {
			value.append(
				"   ["
					+ Integer.toString(i + 1, 16)
					+ "] "
					+ blockURIs[i]
					+ "\n");
		}

		if (checkBlockURIs.length > 0) {
			value.append("---Check Blocks---\n");
		}
		for (int i = 0; i < checkBlockURIs.length; i++) {
			value.append(
				"   ["
					+ Integer.toString(i + 1, 16)
					+ "] "
					+ checkBlockURIs[i]
					+ "\n");
		}

		return value.toString();
	}

}
