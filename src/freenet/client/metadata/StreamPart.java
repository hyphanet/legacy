/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.client.metadata;
import java.net.MalformedURLException;

import freenet.FieldSet;
import freenet.client.FreenetURI;
import freenet.support.Fields;

public class StreamPart extends MetadataPart {
    
	public static final String name = "StreamPart";
    
	public final int revision;
	public final boolean live;
	public final String fecAlgorithm;
	public final int blockCount;
	public final int checkBlockCount;
	public final long startChunk;
	public final long endChunk;
	public final int chunkSeconds;
	
	public final boolean header;
	public final FreenetURI uri;
    
	public StreamPart(int revision, boolean live, String fecAlgorithm, int blockCount,
					  int checkBlockCount, long startChunk, long endChunk, 
					  int chunkSeconds, boolean header, FreenetURI uri) {
		this.revision = revision;
		this.live = live;
		this.fecAlgorithm = fecAlgorithm;
		this.blockCount = blockCount;
		this.checkBlockCount = checkBlockCount;
		this.startChunk = startChunk;
		this.endChunk = endChunk;
		this.chunkSeconds = chunkSeconds;
		this.header = header;
		this.uri = uri;
	}
	
	public StreamPart(FieldSet rawFields,
				  MetadataSettings ms) throws InvalidPartException {
		
		String ver = rawFields.getString("Revision");
		if(ver == null) 
			throw new InvalidPartException(name()+": Requires Revision");
		
		try {
			revision = Fields.hexToInt(ver);
		} catch (NumberFormatException e) {
			throw new InvalidPartException(name()+": "+ver+
										   " is an invalid value for FEC.BlockCount");
		}
		
		String type = rawFields.getString("Type");
		if(type == null)
			throw new InvalidPartException(name()+": Requires Type");
		
		if(type.equalsIgnoreCase("static"))
			live = false;
		else if(type.equalsIgnoreCase("live"))
			live = true;
		else throw new InvalidPartException(name()+": Invalid Type "+type);
		
		String s = rawFields.getString("StartChunk");
		
		if(s == null) {
			startChunk = -1;
		} else {
			try {
				startChunk = Fields.hexToLong(s);
			} catch (NumberFormatException e) {
				throw new InvalidPartException(name()+": "+s+" is an invalid value for"+
											   " StartChunk");
			}
		}

		s = rawFields.getString("EndChunk");
		
		if(s == null) {
			endChunk = -1;
		} else {
			try {
				endChunk = Fields.hexToLong(s);
			} catch (NumberFormatException e) {
				throw new InvalidPartException(name()+": "+s+" is an invalid value for"+
											   " EndChunk");
			}
		}

		s = rawFields.getString("ChunkSeconds");
		
		if(s == null) {
			chunkSeconds = -1;
		} else {
			try {
				chunkSeconds = Fields.hexToInt(s);
			} catch (NumberFormatException e) {
				throw new InvalidPartException(name()+": "+s+" is an invalid value for"+
											   " ChunkSeconds");
			}
		}
		
		s = rawFields.getString("Header");
		
		if(s == null || s.equalsIgnoreCase("false")) {
			header = false;
		} else if(s.equalsIgnoreCase("true")) {
			header = true;
		} else {
			throw new InvalidPartException(name()+": "+s+" is an invalid value for "+
										   "Header");
		}
		
		s = rawFields.getString("URI");
		
		if(s == null) {
			uri = null;
		} else {
			try {
				uri = new FreenetURI(s);
			} catch (MalformedURLException e) {
				throw new InvalidPartException(name()+": "+s+" is an invalid value for"+
											   " URI");
			}
		}
		
		FieldSet fs = rawFields.getSet("FEC");
		if(fs == null)
			throw new InvalidPartException(name()+": Requires FEC Subsection");
		
		fecAlgorithm = fs.getString("AlgoName");
		if(fecAlgorithm == null)
			throw new InvalidPartException(name()+": Requires FEC.AlgoName");
		
		s = fs.getString("BlockCount");
		
		if(s == null)
			throw new InvalidPartException(name()+": Requires FEC.BlockCount");
		
		try {
			blockCount = Fields.hexToInt(s);
		} catch (NumberFormatException e) {
			throw new InvalidPartException(name()+": "+s+
										   " is an invalid value for FEC.BlockCount");
		}
		
		s = fs.getString("CheckBlockCount");
		
		if(s == null)
			throw new InvalidPartException(name()+": Requires FEC.CheckBlockCount");
		
		try {
			checkBlockCount = Fields.hexToInt(s);
		} catch (NumberFormatException e) {
			throw new InvalidPartException(name()+": "+s+" is an invalid value for"+
										   " FEC.CheckBlockCount");
		}
		
	}
	
    public String name() {
		return name;
	}
	
	public boolean isControlPart() {
		return false; // we don't want it to work automatically through *RequestProcess... or do we? FIXME
	}
	
	public void addTo(FieldSet fs) {
		FieldSet me = new FieldSet();
		me.put("Revision", Long.toHexString(revision));
		me.put("Type", (live ? "live" : "static"));
		if(startChunk != -1)
			me.put("StartChunk", Long.toHexString(startChunk));
		if(endChunk != -1)
			me.put("EndChunk", Long.toHexString(endChunk));
		if(chunkSeconds != -1)
			me.put("ChunkSeconds", Long.toHexString(chunkSeconds));
		me.put("Header", header ? "true" : "false");
		if(uri != null)
			me.put("URI", uri.toString());
		FieldSet fec = new FieldSet();
		fec.put("AlgoName", fecAlgorithm);
		fec.put("BlockCount", Long.toHexString(blockCount));
		fec.put("CheckBlockCount", Long.toHexString(checkBlockCount));
		me.put("FEC", fec);
		fs.put(name(), me);
	}
	
	public String toString() {
		return "StreamPart (revision "+revision+", "+(live?"live":"stored")+") using "+
			fecAlgorithm+"("+blockCount+"/"+(blockCount+checkBlockCount)+")";
	}
}
