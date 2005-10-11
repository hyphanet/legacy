package freenet.node.states.FCP;

import freenet.Core;
import freenet.FieldSet;
import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.diagnostics.Diagnostics;
import freenet.fs.dir.NativeFSDirectory;
import freenet.message.client.ClientInfo;
import freenet.message.client.NodeInfo;
import freenet.node.BadStateException;
import freenet.node.Main;
import freenet.node.Node;
import freenet.node.State;
import freenet.node.ds.FSDataStore;
import freenet.support.Logger;
import freenet.transport.tcpAddress;

public class NewInfo extends NewClientRequest {

    public NewInfo(long id, PeerHandler source) {
        super(id, source);
    }
    
    public final String getName() {
        return "New Client Info";
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof ClientInfo))
            throw new BadStateException("expecting ClientInfo");
        FieldSet fs = new FieldSet();
        
        fs.put("EstimatedLoad", Long.toHexString((long)(n.estimatedLoad(false) * 100)));
        
        fs.put("EstimateRateLimitingLoad", Long.toHexString((long)(n.estimatedLoad(true) * 100)));
        
        // Place all of these in their own try/catch set, so they the parameters which are
        // available to us will still be returned
        try {
            fs.put("Architecture", System.getProperty("os.arch"));
        } catch (Throwable e) { }
    
        try {
			fs.put("Processors", Integer.toHexString(Runtime.getRuntime().availableProcessors()));
        } catch (Throwable e) { }
    
        try {
            fs.put("OperatingSystem", System.getProperty("os.name"));
        } catch (Throwable e) { }
    
        try {
            fs.put("OperatingSystemVersion", System.getProperty("os.version"));
        } catch (Throwable e) { }
    
        try {
            fs.put("JavaVendor", System.getProperty("java.vendor.url"));
        } catch (Throwable e) { }
    
        try {
            fs.put("JavaName", System.getProperty("java.vm.name"));
        } catch (Throwable e) { }
    
        try {
            fs.put("JavaVersion", System.getProperty("java.vm.version"));
        } catch (Throwable e) { }
    
        try {
			fs.put("MaximumMemory", Long.toHexString(Runtime.getRuntime().maxMemory()));
        } catch (Throwable e) { }
    
        try {
            fs.put("AllocatedMemory", Long.toHexString(Runtime.getRuntime().totalMemory()));
        } catch (Throwable e) { }
    
        try {
            fs.put("FreeMemory", Long.toHexString(Runtime.getRuntime().freeMemory()));
        } catch (Throwable e) { }
    
        fs.put("DatastoreMax", Long.toHexString(Node.storeSize));
        fs.put("DatastoreFree", Long.toHexString(n.dir.available()));
        fs.put("DatastoreUsed", Long.toHexString(n.dir.used()));
        fs.put("MaxFileSize", Long.toHexString(((FSDataStore)(n.ds)).maxDataSize));
        fs.put("MostRecentTimestamp", Long.toHexString(((NativeFSDirectory)n.dir).mostRecentlyUsedTime()));
        fs.put("LeastRecentTimestamp", Long.toHexString(((NativeFSDirectory)n.dir).leastRecentlyUsedTime()));
    
        fs.put("RoutingTime", Long.toHexString((long)Core.diagnostics.getContinuousValue("routingTime", Diagnostics.MINUTE, Diagnostics.MEAN_VALUE)));
        
        fs.put("AvailableThreads", Long.toHexString(n.availableThreads()));
        fs.put("ActiveJobs", Long.toHexString(n.activeJobs()));

        tcpAddress tcp = Main.getTcpAddress();
        if(tcp!=null) {
            String addr = null;
            try {
                addr = tcp.getHost().getHostAddress();
            } catch (java.net.UnknownHostException e) {
                Core.logger.log(this, "Cannot resolve own address, sending hostname instead",
                                Logger.ERROR);
                addr = tcp.getValName();
            }
            fs.put("NodeAddress", addr);
            fs.put("NodePort", Long.toHexString(tcp.getPort()));
        }
    
        // FIXME: add thread stats stuff here when I work it out ^_^
    
        sendMessage(new NodeInfo(id, fs));
        return null;
    }
}

