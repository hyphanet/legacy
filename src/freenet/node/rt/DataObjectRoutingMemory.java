package freenet.node.rt;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

import freenet.Core;
import freenet.DSAIdentity;
import freenet.FieldSet;
import freenet.Identity;
import freenet.fs.dir.FileNumber;
import freenet.node.BadReferenceException;
import freenet.node.NodeReference;
import freenet.support.CachingFileNumberBuilder;
import freenet.support.DataObject;
import freenet.support.DataObjectPending;
import freenet.support.DataObjectUnloadedException;
import freenet.support.HexUtil;
import freenet.support.Logger;
import freenet.support.io.ReadInputStream;
import freenet.support.io.WriteOutputStream;

/**
 * Acts as a handle for a given node and a container for its node reference.
 * Named properties of the node are retrieved through this handle.
 * 
 * @author tavin
 */
class DataObjectRoutingMemory implements RoutingMemory, DataObject {

	private final DataObjectRoutingStore routingStore;

	final Identity id;
	NodeReference noderef;

	//Used to speed up on Identity+propname-to-FileNumber building  
	CachingFileNumberBuilder fileNumberCache = new CachingFileNumberBuilder(10000,true,true);

	private static boolean logDebug = true;

	public String toString() {
		return "DataObjectRoutingMemory:"
			+ noderef
			+ ':'
			+ HexUtil.bytesToHex(id.fingerprint());
	}

	DataObjectRoutingMemory(
		DataObjectRoutingStore routingStore,
		Identity id,
		NodeReference noderef) {
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		this.routingStore = routingStore;
		this.id = id;
		this.noderef = noderef;
	}

	DataObjectRoutingMemory(
		DataObjectRoutingStore routingStore,
		DataObjectPending dop)
		throws BadReferenceException, IOException {
		logDebug = Core.logger.shouldLog(Logger.DEBUG, this);
		this.routingStore = routingStore;
		DataInputStream dis = dop.getDataInputStream();
		int ver = dis.readInt();
		if(ver != 1) {
		    throw new IOException("old version routing table");
		}
		ReadInputStream ros = new ReadInputStream(dis); 
		FieldSet fs =
		    new FieldSet(ros);
		this.id = new DSAIdentity(fs);
	    fs = new FieldSet(ros);
	    if(fs.size() == 0)
	        this.noderef = null;
	    else
		this.noderef = new NodeReference(fs);
		dop.resolve(this);
	}

	/**
	 * @return the node's identity
	 */
	public final Identity getIdentity() {
		return id;
	}

	/**
	 * @return the node's node reference
	 */
	public final NodeReference getNodeReference() {
		return noderef;
	}

	/**
	 * @return the named DataObject, or null if not found
	 */
	public final DataObject getProperty(String name)
		throws DataObjectUnloadedException {
		
		//long startedTime = -1;
		//if (logDebug)
		//	Core.logger.log(this, "getProperty(" + name + ")", Logger.DEBUG);
		FileNumber fn = fileNumberCache.build(id,name);
		try {
			//startedTime = System.currentTimeMillis();
			DataObject o = routingStore.rtProps.get(fn);
			//long gotObjectTime = System.currentTimeMillis();
			//long len2 = gotObjectTime - startedTime;
			//if (logDebug || len2 > 500)
			//	Core.logger.log(this, "getProperty(" + name + ") getting DataObject took " + len2, len2 > 500 ? Logger.MINOR : Logger.DEBUG);
			return o;
		} catch (DataObjectUnloadedException e) {
			//long thrownTime = System.currentTimeMillis();
			//long len3 = thrownTime - startedTime;
			//if (logDebug || len3 > 500)
			//	Core.logger.log(this, "getProperty(" + name + ") throwing DataObjectUnloadedException; get(" + fn + ") took " + len3, len3 > 500 ? Logger.MINOR : Logger.DEBUG);
			throw e;
		}
		
	}

	/**
	 * Schedules the named DataObject for saving to disk. Could be better
	 * named!
	 */
	public final void setProperty(String name, DataObject o) {
		routingStore.rtProps.set(fileNumberCache.build(id,name), o);
	}

	// reuse instance for getDataLength() computations
	private static final ByteArrayOutputStream bout =
		new ByteArrayOutputStream();

	/**
	 * Write the node ref's field-set.
	 */
	public final void writeDataTo(DataOutputStream out) throws IOException {
	    out.writeInt(1);
	    WriteOutputStream wos = new WriteOutputStream(out);
	    id.getFieldSet().writeFields(wos);
	    if(noderef == null)
	        new FieldSet().writeFields(wos);
	    else
	        noderef.getFieldSet().writeFields(wos);
	}

	/**
	 * Determine the field-set's byte length when written out.
	 */
	public final int getDataLength() {
		synchronized (bout) {
			try {
				writeDataTo(new DataOutputStream(bout));
				return bout.size();
			} catch (IOException e) {
				return 0;
			} finally {
				bout.reset();
			}
		}
	}

    /**
     * @param nr
     */
    public synchronized void updateReference(NodeReference nr) {
        if(nr.supersedes(noderef)) noderef = nr;
    }
}
