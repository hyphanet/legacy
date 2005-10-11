package freenet.message.client;

import freenet.FieldSet;

/**
 * FCP message to tell client that FEC encoding 
 * completed successfully.
 */
public class BlocksEncoded extends ClientMessage {

    public static final String messageName = "BlocksEncoded";
    
    public BlocksEncoded(long id, int nBlocks, int blockSize) {
        super(id, new FieldSet());
        if (nBlocks > 0) {
            otherFields.put("BlockCount", Integer.toHexString(nBlocks));
        }

        if (blockSize > 0) {
            otherFields.put("BlockSize", Integer.toHexString(blockSize));
        }

        close = false;
        
    }

    public String getMessageName() {
        return messageName;
    }
}



