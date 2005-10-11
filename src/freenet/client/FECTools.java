package freenet.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Hashtable;

import freenet.Core;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InfoPart;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.SplitFile;
import freenet.config.Params;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.SegmentHeader;
import freenet.support.Bucket;
import freenet.support.BucketFactory;
import freenet.support.BucketTools;
import freenet.support.Logger;

/**
 * Class to handle FEC encoding and decoding. The purpose of this class is to
 * encapsulate the FEC functionality in one place so that it can be used by
 * FCPClient, InternalClient and the FCP FEC implementation. Some client
 * application writers might also want to use it directly.
 */
public class FECTools {
	public final static String NULLDECODERNAME = "NONREDUNDANT!";

	private FECFactory fecFactory = null;
	private int codecCacheSize = 1;
	private BucketFactory bf = null;

	////////////////////////////////////////////////////////////
	// Enforce limits on the number of concurrent FEC
	// encodes / decodes.
	////////////////////////////////////////////////////////////
	private int runningCodecs = 0;
	private int maxCodecs = 1;

	private synchronized void waitForCodec() throws InterruptedException {
		while (runningCodecs >= maxCodecs) {
			Core.logger.log(
				this,
				"Hit concurrent codec limit. Waiting for codec...",
				Logger.DEBUG);
			wait(1000);
		}
		runningCodecs++;
	}

	private synchronized void releaseCodec() {
		runningCodecs--;
		notifyAll();
	}

	////////////////////////////////////////////////////////////
	// codecCacheSize is the number of codec (either encoder or decoder)
	// instances to keep around. Creating and destroying codecs may
	// be somewhat time consuming depending on the plugin implementation.
	// The cache reduces thrashing at the expense of increasing memory
	// usage.
	// 
	// maxConcurrentCodecs is the maximum number of concurrent encodes
	// or decodes to allow. My plugin implementations bound memory
	// consumption at <= 24Mb per encode or decode. So in the worst
	// case scenario you are allowing maxConncurrentCodecs * 24Mb of
	// memory usage.
	//
	// Once maxConcurrentCodecs is exceeded calls to encodeSegment()
	// and decodeSegment() block until a running
	// encode/decode finishes.
	//
	// --gj
	//
	public FECTools(
		Params params,
		BucketFactory bf,
		int codecCacheSize,
		int maxConcurrentCodecs)
		throws IOException {
		this.fecFactory = new FECFactory();
		this.bf = bf;
		this.codecCacheSize = codecCacheSize;
		this.maxCodecs = maxConcurrentCodecs;
		loadFECPlugins(params);
	}

	public final BucketFactory getBucketFactory() {
		return bf;
	}

	// Called in ctr. Only needs to be called explictly
	// if config file changes.

	// REDFLAG: Test this code
	// REDFLAG: Explain dynamic loading

	/*
	 * # FEC implementation plugins
	 * FEC.Encoders.0.class="leet.haXORz.Onion33Encoder"
	 * FEC.Encoders.1.class="leet.haXORz.Onion50Encoder"
	 * FEC.Decoders.0.class="leet.haXORz.Onion33Decoder"
	 * FEC.Decoders.1.class="leet.haXORz.Onion50Decoder"
	 */
	public void loadFECPlugins(Params params) throws IOException {
		fecFactory.flush();

		final String ROOT_PARAM_NAME = "FEC";

		Params rootSet = (Params) params.getSet(ROOT_PARAM_NAME);
		if (rootSet == null) {
			// REDFLAG: We don't seem to be able to read the default
			//          values as a set.
			//
			// So we do this hack to read the parameter values.
			Params top = new Params();
			boolean gotAtLeastOne = false;
			String defaultEncoder =
				params.getString(ROOT_PARAM_NAME + ".Encoders.0.class");
			if (defaultEncoder != null) {
				Params encoders = new Params();
				Params zero = new Params();
				zero.put("class", defaultEncoder);
				encoders.put("0", zero);
				top.put("Encoders", encoders);
				gotAtLeastOne = true;
				//System.err.println("Read default FEC encoder class using
				// hack: " + defaultEncoder);
			}
			String defaultDecoder =
				params.getString(ROOT_PARAM_NAME + ".Decoders.0.class");
			if (defaultDecoder != null) {
				Params decoders = new Params();
				Params zero = new Params();
				zero.put("class", defaultDecoder);
				decoders.put("0", zero);
				top.put("Decoders", decoders);
				gotAtLeastOne = true;
				//System.err.println("Read default FEC decoder class using
				// hack: " + default);
			}

			if (!gotAtLeastOne) {
				throw new IOException("Can't parse FEC Plugin parameters.");
			}
			params.put(ROOT_PARAM_NAME, top);
		}
		Params root = (Params) params.getSet(ROOT_PARAM_NAME);

		// Encoders
		int i = 0;
		Params encoders = (Params) root.getSet("Encoders");
		if (encoders != null) {
			for (i = 0; i < encoders.size(); i++) {
				Params encoder = (Params) encoders.getSet(Integer.toString(i));
				if (encoder == null) {
					continue;
				}
				String cls = encoder.getString("class");
				if (cls == null) {
					Core.logger.log(
						this,
						"Couldn't parse FEC encoder params [" + cls + "]",
						Logger.DEBUG);
					continue;
				}

				if (fecFactory.registerEncoder(cls)) {
					Core.logger.log(
						this,
						"Loaded FECEncoder [" + cls + "] ",
						Logger.DEBUG);
				} else {
					Core.logger.log(
						this,
						"FAILED to Load FECEncoder [" + cls + "]",
						Logger.DEBUG);
				}
			}
		}

		// Decoders
		Params decoders = (Params) root.getSet("Decoders");
		if (decoders != null) {
			for (i = 0; i < decoders.size(); i++) {
				Params decoder = (Params) decoders.getSet(Integer.toString(i));
				if (decoder == null) {
					continue;
				}
				String cls = decoder.getString("class");
				if (cls == null) {
					Core.logger.log(
						this,
						"Couldn't parse FEC decoder params [" + cls + "]",
						Logger.DEBUG);
					continue;
				}
				if (fecFactory.registerDecoder(cls)) {
					Core.logger.log(
						this,
						"Loaded FECDecoder [" + cls + "]",
						Logger.DEBUG);
				} else {
					Core.logger.log(
						this,
						"FAILED to Load FECDecoder [" + cls + "]",
						Logger.DEBUG);
				}
			}
		}

	}

	// algorithm == null, is allowed. It causes the default encodername to be
	// used.
	public SegmentHeader[] segmentFile(
		long id,
		String algorithm,
		long fileSize)
		throws IOException {
		FECEncoder encoder = null;
		try {
			// Throws if no encoder can be found.
			encoder = getEncoder(algorithm, fileSize);

			int segmentCount = encoder.getSegmentCount();
			SegmentHeader[] ret = new SegmentHeader[segmentCount];

			int dataBlockOffset = 0;
			int checkBlockOffset = 0;
			long offset = 0;
			for (int i = 0; i < segmentCount; i++) {
				int segmentSize = encoder.getSegmentSize(i);
				int blocksRequired = encoder.getK(i);
				int blockCount = blocksRequired;
				int checkBlockCount = encoder.getN(i) - blocksRequired;
				int blockSize = encoder.getBlockSize(i);
				int checkBlockSize = encoder.getCheckBlockSize(i);

				ret[i] =
					new SegmentHeader(
						id,
						encoder.getName(),
						fileSize,
						offset,
						blocksRequired,
						blockCount,
						blockSize,
						checkBlockCount,
						checkBlockSize,
						dataBlockOffset,
						checkBlockOffset,
						segmentCount,
						i);
				dataBlockOffset += blockCount;
				checkBlockOffset += checkBlockCount;
				offset += segmentSize;
			}

			return ret;

		} finally {
			softRelease(encoder, fileSize);
		}

	}

	public Bucket[] encodeSegment(
		SegmentHeader header,
		int[] checkBlocks,
		Bucket[] blocks)
		throws IOException {

		FECEncoder encoder = null;
		try {
			waitForCodec();

			if (blocks.length == 0) {
				throw new IllegalArgumentException(
					"Expecting "
						+ header.getBlockCount()
						+ " data blocks.  Got "
						+ blocks.length
						+ ".");
			}

			// Throws if no encoder can be found.
			encoder =
				getEncoder(header.getFECAlgorithm(), header.getFileLength());

			// It is ok for checkBlocks to be null
			Bucket[] check =
				encoder.encode(header.getSegmentNum(), blocks, checkBlocks);

			// BUG found 20030101, This is just plain wrong, but i don't think
			// it
			//                     was ever run.
			//
			// REDFLAG: was this code path really never excercised? check fish
			// + mihi's sources
			//     
			//  if (checkBlocks == null) {
			//                  return check;
			//              }

			//              Bucket[] ret = new Bucket[checkBlocks.length];
			//              int i = 0;
			//              for (i = 0; i < checkBlocks.length; i++) {
			//                  System.err.println("checkBlocks[" + i + "]: " + checkBlocks[i] +
			//                                     " check.length: " + check.length);
			//                  ret[i] = check[checkBlocks[i]];
			//                  check[checkBlocks[i]] = null;
			//              }

			//              // Free buckets that aren't returned.
			//              for (i = 0; i < check.length; i++) {
			//                  if (check[i] != null) {
			//                      bf.freeBucket(check[i]);
			//                  }
			//              }
			// return ret;
			return check;

		} catch (InterruptedException ie) {
			throw new InterruptedIOException(ie.toString());
		} finally {
			softRelease(encoder, header.getFileLength());
			releaseCodec();
		}

	}

	// REDFLAG: remove eventually
	private static final String arrayToString(int[] array) {
		StringBuffer ret = new StringBuffer();
		for (int i = 0; i < array.length; i++) {
			ret.append(Integer.toString(array[i]) + " ");
		}
		return ret.toString().trim();
	}

	// optionally, sends missing check blocks too
	// if values >= header.getBlockCount()
	// are present in requestedBlocks.
	public Bucket[] decodeSegment(
		SegmentHeader header,
		int[] dataBlocks,
		int[] checkBlocks,
		int[] requestedBlocks,
		Bucket[] blocks)
		throws IOException {

		if (dataBlocks.length == header.getBlocksRequired()) {
			throw new IllegalArgumentException("You already have all the data blocks.");
		}

		if (blocks.length < header.getBlocksRequired()) {
			throw new IllegalArgumentException(
				"Expecting "
					+ header.getBlocksRequired()
					+ " blocks.  Got "
					+ blocks.length
					+ ".");
		}

		Bucket[] decoded = null;
		int[] requestedCheckBlocks = new int[0];
		int[] requestedDataBlocks = requestedBlocks;

		FECDecoder decoder = null;
		try {
			waitForCodec();
			decoder =
				getDecoder(header.getFECAlgorithm(), header.getFileLength());

			int segment = header.getSegmentNum();

			decoder.setSegment(segment);

			int i = 0;
			for (i = 0; i < dataBlocks.length; i++) {
				decoder.putBucket(blocks[i], dataBlocks[i]);
			}

			for (i = 0; i < checkBlocks.length; i++) {
				decoder.putBucket(
					blocks[i + dataBlocks.length],
					checkBlocks[i]);
			}

			if (requestedBlocks == null) {
				// Get list of all missing data blocks.
				int index = 0;
				requestedBlocks =
					new int[header.getBlocksRequired() - dataBlocks.length];
				boolean[] existing = new boolean[header.getBlocksRequired()];
				for (i = 0; i < dataBlocks.length; i++) {
					existing[dataBlocks[i]] = true;
				}
				for (i = 0; i < existing.length; i++) {
					if (!existing[i]) {
						requestedBlocks[index] = i;
						index++;
					}
				}
				// hmmm... no points for style.
			}

			// Check to see if check blocks were requested too
			for (int j = 0; j < requestedBlocks.length; j++) {
				if (requestedBlocks[j] >= header.getBlockCount()) {
					// Set up to send missing check blocks too.
					requestedDataBlocks = new int[j];
					System.arraycopy(
						requestedBlocks,
						0,
						requestedDataBlocks,
						0,
						j);
					requestedCheckBlocks = new int[requestedBlocks.length - j];
					System.arraycopy(
						requestedBlocks,
						j,
						requestedCheckBlocks,
						0,
						requestedCheckBlocks.length);
					break;
				}
			}

			decoded = new Bucket[requestedDataBlocks.length];
			if (!decoder.decode(requestedDataBlocks, decoded)) {
				throw new IOException("FEC Decode failed.");
			}

		} catch (InterruptedException ie) {
			throw new InterruptedIOException(ie.toString());
		} finally {
			softRelease(decoder, header.getFileLength());
			releaseCodec();
		}

		if (requestedCheckBlocks.length > 0) {
			// Encode requested check blocks
			return encodeMissingCheckBlocks(
				header,
				blocks,
				decoded,
				dataBlocks,
				requestedDataBlocks,
				requestedCheckBlocks);
		}

		return decoded;
	}

	// returns decoded concatinated with requested check blocks
	// frees decoded buckets on exception.
	private Bucket[] encodeMissingCheckBlocks(
		SegmentHeader header,
		Bucket[] blocks,
		Bucket[] decoded,
		int[] dataBlocks,
		int[] requestedDataBlocks,
		int[] requestedCheckBlocks)
		throws IOException {

		// Collect data blocks.
		Bucket[] allData = new Bucket[header.getBlockCount()];
		int k = 0;
		for (k = 0; k < dataBlocks.length; k++) {
			// Data blocks that were passed in.
			allData[dataBlocks[k]] = blocks[k];
		}
		for (k = 0; k < requestedDataBlocks.length; k++) {
			// Data blocks that we just decoded;
			allData[requestedDataBlocks[k]] = decoded[k];
		}

		// REDFLAG: remove paranoid check eventually.
		for (k = 0; k < allData.length; k++) {
			if (allData[k] == null) {
				throw new IOException("Assertion Failure: k = " + k);
			}
		}

		Bucket[] decodedAndChecks = null;
		Bucket[] missingChecks = null;
		FECEncoder encoder = null;
		try {
			waitForCodec();

			// Throws if no encoder can be found.
			encoder =
				getEncoder(header.getFECAlgorithm(), header.getFileLength());

			missingChecks =
				encoder.encode(
					header.getSegmentNum(),
					allData,
					requestedCheckBlocks);

			decodedAndChecks =
				new Bucket[decoded.length + missingChecks.length];
			System.arraycopy(decoded, 0, decodedAndChecks, 0, decoded.length);
			System.arraycopy(
				missingChecks,
				0,
				decodedAndChecks,
				decoded.length,
				missingChecks.length);

		} catch (InterruptedException ie) {
			throw new InterruptedIOException(ie.toString());
		} finally {
			softRelease(encoder, header.getFileLength());
			releaseCodec();
			if (decodedAndChecks == null) {
				// Make sure decoded buckets are released on exception.
				BucketTools.freeBuckets(bf, decoded);
			}
		}
		return decodedAndChecks;
	}

	public static class HeaderAndMap {
		public HeaderAndMap(SegmentHeader header, BlockMap map) {
			this.header = header;
			this.map = map;
		}
		public final SegmentHeader header;
		public final BlockMap map;
	}

	public HeaderAndMap[] segmentSplitFile(long id, Bucket splitFileMetadata)
		throws IOException {

		Metadata md = null;
		InputStream in = splitFileMetadata.getInputStream();
		try {
			md = new Metadata(in, new MetadataSettings());
		} catch (InvalidPartException ivp) {
			ivp.printStackTrace();
			throw new IOException("Couldn't parse SplitFile metadata.");
		} finally {
			in.close();
		}

		SplitFile sf = null;
		try {
			sf = (SplitFile) md.getDefaultDocument().getControlPart();
		} catch (Exception e) {
		}

		if (sf == null) {
			throw new IOException("Couldn't parse SplitFile.");
		}

		String algoName = sf.getFECAlgorithm();

		SegmentHeader[] headers = null;
		if (algoName == null) {
			// I code on under protest.
			// We should not encourage people to use
			// nonredundant splitfiles. --gj

			// Add special case code to
			// support non-redundant SplitFiles.
			algoName = NULLDECODERNAME;
			headers = new SegmentHeader[1];
			headers[0] =
				new SegmentHeader(
					id,
					NULLDECODERNAME,
					sf.getSize(),
					0,
					sf.getBlockCount(),
					sf.getBlockCount(),
					0 /* we don't really know */
			, 0, 0, 0, 0, 1, 0);

		} else {
			headers = segmentFile(id, algoName, sf.getSize());
		}

		HeaderAndMap[] ret = new HeaderAndMap[headers.length];

		int i;
		for (i = 0; i < headers.length; i++) {
			String[] dataCHKs = new String[headers[i].getBlockCount()];
			String[] allDataCHKS = sf.getBlockURIs();

			System.arraycopy(
				allDataCHKS,
				headers[i].getDataBlockOffset(),
				dataCHKs,
				0,
				dataCHKs.length);

			String[] checkCHKs = new String[headers[i].getCheckBlockCount()];
			String[] allCheckCHKS = sf.getCheckBlockURIs();

			System.arraycopy(
				allCheckCHKS,
				headers[i].getCheckBlockOffset(),
				checkCHKs,
				0,
				checkCHKs.length);

			ret[i] =
				new HeaderAndMap(
					headers[i],
					new BlockMap(id, dataCHKs, checkCHKs));
		}
		return ret;
	}

	public void makeSplitFile(
		SegmentHeader[] headers,
		BlockMap[] maps,
		String description,
		String mimeType,
		String checksum,
		Bucket metadata)
		throws IOException {
		boolean logDEBUG = Core.logger.shouldLog(Logger.DEBUG, this);
		if (headers == null
			|| maps == null
			|| headers.length != maps.length
			|| headers.length < 1) {
			throw new IllegalArgumentException();
		}

		// Set reasonable defaults
		if (description == null) {
			description = "file";
		}

		if (mimeType == null) {
			mimeType = "application/octet-stream";
		}

		if (logDEBUG)
			Core.logger.log(
				this,
				"description: "
					+ description
					+ ", mimeType: "
					+ mimeType
					+ ", bucket: "
					+ metadata,
				Logger.DEBUG);

		int blockCount = 0;
		int checkBlockCount = 0;
		int i = 0;
		for (i = 0; i < maps.length; i++) {
			blockCount += maps[i].getDataBlocks().length;
			checkBlockCount += maps[i].getCheckBlocks().length;
		}

		if (logDEBUG)
			Core.logger.log(
				this,
				"blockCount="
					+ blockCount
					+ ", checkBlockCount="
					+ checkBlockCount,
				Logger.DEBUG);

		String[] dataBlocks = new String[blockCount];
		String[] checkBlocks = new String[checkBlockCount];

		int dataOffset = 0;
		int checkOffset = 0;

		for (i = 0; i < maps.length; i++) {
			//System.err.println("Dumping BlockMap: " + i);
			//maps[i].dump();
			System.arraycopy(
				maps[i].getDataBlocks(),
				0,
				dataBlocks,
				dataOffset,
				maps[i].getDataBlocks().length);

			dataOffset += maps[i].getDataBlocks().length;

			System.arraycopy(
				maps[i].getCheckBlocks(),
				0,
				checkBlocks,
				checkOffset,
				maps[i].getCheckBlocks().length);

			checkOffset += maps[i].getCheckBlocks().length;
		}

		// Paranoid check
		for (i = 0; i < dataBlocks.length; i++) {
			if (dataBlocks[i] == null) {
				throw new IllegalArgumentException(
					"There was a null data block in a BlockMap. " + i);
			}
		}
		for (i = 0; i < checkBlocks.length; i++) {
			if (checkBlocks[i] == null) {
				throw new IllegalArgumentException(
					"There was a null check block in a BlockMap. " + i);
			}
		}

		try {
			// REDFLAG: check that algo is same in every header?
			// REDFLAG: need to rewrite SplitFile to handle different varying
			// block sizes?
			SplitFile sf =
				new SplitFile(
					headers[0].getFileLength(),
					dataBlocks,
					checkBlocks,
					headers[0].getFECAlgorithm());

			if (logDEBUG)
				Core.logger.log(this, "Got SplitFile", Logger.DEBUG);

			DocumentCommand doc = new DocumentCommand(new MetadataSettings());
			InfoPart info = new InfoPart(description, mimeType, checksum);
			doc.addPart(info);
			doc.addPart(sf);
			if (logDEBUG)
				Core.logger.log(this, "Constructed doc", Logger.DEBUG);

			Metadata md = new Metadata(new MetadataSettings());
			if (logDEBUG)
				Core.logger.log(this, "Got metadata", Logger.DEBUG);
			md.addDocument(doc);
			OutputStream os;
			try {
				os = metadata.getOutputStream();
			} catch (Throwable e) {
				if (logDEBUG)
					Core.logger.log(
						this,
						"Getting OutputStream for metadata failed:",
						e,
						Logger.DEBUG);
				os = new freenet.support.io.NullOutputStream();
			}
			md.writeTo(os);
			os.close();
			if (logDEBUG)
				md.writeTo(Core.logStream);
			if (metadata.size() == 0)
				throw new IOException("written zero length metadata");
		} catch (InvalidPartException ivp) {
			if (logDEBUG)
				Core.logger.log(
					this,
					"Got Exception in makeSplitFile",
					ivp,
					Logger.DEBUG);
			throw new IOException("Couldn't create SplitFile metadata.");
		}
		if (logDEBUG)
			Core.logger.log(
				this,
				"Metadata size now: " + metadata.size(),
				Logger.DEBUG);
		if (metadata.size() == 0)
			throw new IOException("Something left metadata size at 0");
	}

	////////////////////////////////////////////////////////////
	// Manage caching
	////////////////////////////////////////////////////////////

	public boolean supportsEncoder(String name) {
		return fecFactory.isRegistered(name, true);
	}

	public boolean supportsDecoder(String name) {
		return fecFactory.isRegistered(name, false);
	}

	// returns an *initialized* encoder.
	protected FECEncoder getEncoder(String algorithm, long totalFileLength)
		throws IOException {

		if (algorithm == null) {
			algorithm = fecFactory.getDefaultEncoder();
		}

		String mangledName =
			"encoder_" + algorithm + Long.toString(totalFileLength, 16);

		FECEncoder ret = null;
		synchronized (cachedCodecs) {
			ret = (FECEncoder) cachedCodecs.get(mangledName);
			if (ret != null) {
				// REDFLAG: Datastructure abuse. ok if cache is small
				for (Enumeration e = cachedCodecs.keys();
					e.hasMoreElements();
					) {
					Object key = e.nextElement();
					if (cachedCodecs.get(key) == ret) {
						cachedCodecs.remove(key);
					}
				}
				return ret;
			}
		}

		ret = fecFactory.getEncoder(algorithm);
		if (ret == null) {
			throw new IOException("Couldn't get FECEncoder: " + algorithm);
		}

		if (!ret.init(totalFileLength, bf)) {
			throw new IOException(
				"Couldn't initialize FECEncoder: " + algorithm);
		}

		return ret;
	}

	// returns an *initialized* decoder.
	protected FECDecoder getDecoder(String algorithm, long totalFileLength)
		throws IOException {

		String mangledName =
			"decoder_" + algorithm + Long.toString(totalFileLength, 16);

		FECDecoder ret = null;
		synchronized (cachedCodecs) {
			ret = (FECDecoder) cachedCodecs.get(mangledName);
			if (ret != null) {
				// REDFLAG: Datastructure abuse. ok if cache is small
				for (Enumeration e = cachedCodecs.keys();
					e.hasMoreElements();
					) {
					Object key = e.nextElement();
					if (cachedCodecs.get(key) == ret) {
						cachedCodecs.remove(key);
					}
				}
				return ret;
			}
		}

		ret = fecFactory.getDecoder(algorithm);
		if (ret == null) {
			throw new IOException("Couldn't get FECDecoder: " + algorithm);
		}

		if (!ret.init(totalFileLength, bf)) {
			throw new IOException(
				"Couldn't initalize FECDecoder: " + algorithm);
		}

		return ret;
	}

	// If we've hit the cache size, remove
	// one codec that isn't the current one.
	// REQUIRES: cachedCodecs lock
	protected void bumpDown() {
		if ((cachedCodecs.size() < codecCacheSize)
			|| (cachedCodecs.size() == 0)) {
			return;
		}

		Object obj = cachedCodecs.elements().nextElement();

		for (Enumeration e = cachedCodecs.keys(); e.hasMoreElements();) {
			Object key = e.nextElement();
			if (cachedCodecs.get(key) == obj) {
				cachedCodecs.remove(key);
			}
		}
		if (obj instanceof FECEncoder) {
			((FECEncoder) obj).release();
		} else {
			((FECDecoder) obj).release();
		}
	}

	protected void softRelease(FECEncoder encoder, long len) {
		if (encoder == null) {
			return;
		}

		synchronized (cachedCodecs) {
			if (cachedCodecs.contains(encoder)) {
				throw new RuntimeException("Assertion Failure: cacheCodecs.contains(encoder)");
			}

			bumpDown();
			String mangledName =
				"encoder_" + encoder.getName() + Long.toString(len, 16);

			cachedCodecs.put(mangledName, encoder);
		}
	}

	protected void softRelease(FECDecoder decoder, long len) {
		if (decoder == null) {
			return;
		}

		synchronized (cachedCodecs) {
			if (cachedCodecs.contains(decoder)) {
				throw new RuntimeException("Assertion Failure: cacheCodecs.contains(decoder)");
			}
			bumpDown();
			String mangledName =
				"decoder_" + decoder.getName() + Long.toString(len, 16);
			cachedCodecs.put(mangledName, decoder);
		}
	}

	// Make sure cached instances are always released.
	protected void finalize() throws Throwable {
		for (Enumeration e = cachedCodecs.elements(); e.hasMoreElements();) {
			Object obj = e.nextElement();
			if (obj instanceof FECEncoder) {
				((FECEncoder) obj).release();
			} else if (obj instanceof FECDecoder) {
				((FECDecoder) obj).release();
			}
		}
	}

	Hashtable cachedCodecs = new Hashtable();
}
