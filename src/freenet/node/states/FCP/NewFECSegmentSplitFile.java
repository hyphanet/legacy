package freenet.node.states.FCP;

import java.io.OutputStream;

import freenet.MessageObject;
import freenet.PeerHandler;
import freenet.client.FECTools;
import freenet.message.client.Failed;
import freenet.message.client.FEC.BlockMap;
import freenet.message.client.FEC.FECSegmentSplitFile;
import freenet.message.client.FEC.SegmentHeader;
import freenet.node.BadStateException;
import freenet.node.Node;
import freenet.node.State;
import freenet.support.Bucket;
import freenet.support.BucketFactory;

public class NewFECSegmentSplitFile extends NewClientRequest {

    public NewFECSegmentSplitFile(long id, PeerHandler source) {
        super(id, source);
    }
    
    public final String getName() {
        return "New FEC Segment SplitFile";
    }

    public State received(Node n, MessageObject mo) throws BadStateException {
        if (!(mo instanceof FECSegmentSplitFile))
            throw new BadStateException("expecting FECSegmentSplitFile");

        BucketFactory bf = Node.fecTools.getBucketFactory();

        Bucket sfMeta = null;
        try {
            FECSegmentSplitFile msg = (FECSegmentSplitFile)mo;

            long tlen = msg.getDataLength();

            // Read SplitFile metadata
            sfMeta = bf.makeBucket(tlen);
            OutputStream out = sfMeta.getOutputStream();
            try { 
                copyFully(msg.getDataStream(), out, tlen); 
            }
            catch (Exception e) {
                sendMessage(new Failed( id, "Couldn't read SplitFile Metadata."));
                return null;
            }
            finally {
                out.close();
            }

            //BucketTools.dumpBucket(sfMeta, "__DEBUG_sfmeta.dat");

            FECTools.HeaderAndMap[] headers = Node.fecTools.segmentSplitFile(id, sfMeta);

            int i = 0;
            for (i = 0; i < headers.length; i++) {
                SegmentHeader header = headers[i].header;
                header.setClose(false);
                BlockMap map = headers[i].map;
                // Drop the connection after the final header, map
                // pair is sent.
                boolean isLast = (i == headers.length - 1);
                map.setClose(isLast);
                sendMessage(header);
                sendMessage(map);
            }
        }
        catch (Exception e) {
            e.printStackTrace(); // REDFLAG: remove
            sendMessage(new Failed( id, e.getMessage()));
        }
        finally { 
            if (sfMeta != null) {
                try {
                    bf.freeBucket(sfMeta);
                }
                catch (Exception e) {
                }
            }
        }

        return null;
    }
}

