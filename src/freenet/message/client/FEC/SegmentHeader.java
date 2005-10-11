package freenet.message.client.FEC;

import java.io.IOException;

import freenet.BaseConnectionHandler;
import freenet.FieldSet;
import freenet.RawMessage;
import freenet.message.client.ClientMessage;
import freenet.support.io.ReadInputStream;

/** Message containing information about a FEC Splitfile
 *  segment.
 */
public class SegmentHeader extends ClientMessage {

    String algoName = null;
    long fileLength = -1;
    long offset = -1;

    int blocksRequired = -1;
    int blockCount = -1;
    int blockSize = -1;

    int checkBlockCount = -1;
    int checkBlockSize = -1;

    int dataBlockOffset = -1;
    int checkBlockOffset = -1;

    int segments = -1;
    int segmentNum = -1;

    public static final String messageName = "SegmentHeader";

    // From wire
    public SegmentHeader(BaseConnectionHandler source, RawMessage raw) {
        super(source, raw);
        if (!formatError) {
            formatError = readFrom(otherFields);
            close = false;
        }
    }

    // From a ReadInputStream.
    public SegmentHeader(long id, ReadInputStream in) throws IOException {
        super(id, new FieldSet()); 
        String msgName = in.readln();
        if (!msgName.equals("SegmentHeader")) {
            throw new IOException("Input stream doesn't contain a SegmentHeader msg!");
        }
        FieldSet fs = new FieldSet(in);
        formatError = readFrom(fs);
        close = false;
    }

    // To wire.
    public SegmentHeader(long id,
                         String algoName,
                         long fileLength,
                         long offset,
                         int blocksRequired,
                         int blockCount,
                         int blockSize,
                         int checkBlockCount,
                         int checkBlockSize,
                         int dataBlockOffset,
                         int checkBlockOffset,
                         int segments,
                         int segmentNum) {
        super(id, new FieldSet()); 
        
        // grrrr.... read for dumb errors REDFLAG: revisit

        this.algoName = algoName;
        if (algoName != null) { 
            otherFields.put("FECAlgorithm", algoName); 
        }

        // I write zero values to wire to make parsing
        // easier for clients.
        this.fileLength = fileLength;
        otherFields.put("FileLength", Long.toHexString(fileLength));

        this.offset = offset;
        otherFields.put("Offset", Long.toHexString(offset));

        this.blocksRequired = blocksRequired;
        otherFields.put("BlocksRequired", Integer.toHexString(blocksRequired));

        this.blockCount = blockCount;
        otherFields.put("BlockCount", Integer.toHexString(blockCount));

        this.blockSize = blockSize;
        otherFields.put("BlockSize", Integer.toHexString(blockSize));

        this.checkBlockCount = checkBlockCount;
        otherFields.put("CheckBlockCount", Integer.toHexString(checkBlockCount));

        this.checkBlockSize = checkBlockSize;
        otherFields.put("CheckBlockSize", Integer.toHexString(checkBlockSize));

        this.dataBlockOffset = dataBlockOffset;
        otherFields.put("DataBlockOffset", Integer.toHexString(dataBlockOffset));

        this.checkBlockOffset = checkBlockOffset;
        otherFields.put("CheckBlockOffset", Integer.toHexString(checkBlockOffset));

        this.segments = segments;
        otherFields.put("Segments", Integer.toHexString(segments));

        this.segmentNum = segmentNum;
        otherFields.put("SegmentNum", Integer.toHexString(segmentNum));

        close = false;
    }

    protected  boolean readFrom(FieldSet fs) {
        boolean ret = false;
        try {
            algoName = fs.getString("FECAlgorithm");
            fileLength = Long.parseLong(fs.getString("FileLength"), 16);
            offset = Long.parseLong(fs.getString("Offset"), 16);
            
            blockCount = Integer.parseInt(fs.getString("BlockCount"), 16);
            blockSize = Integer.parseInt(fs.getString("BlockSize"), 16);
            
            checkBlockCount = Integer.parseInt(fs.getString("CheckBlockCount"), 16);
            checkBlockSize = Integer.parseInt(fs.getString("CheckBlockSize"), 16);
            
            segments = Integer.parseInt(fs.getString("Segments"), 16);                
            segmentNum = Integer.parseInt(fs.getString("SegmentNum"), 16);                
            
            blocksRequired = Integer.parseInt(fs.getString("BlocksRequired"), 16);
            
            dataBlockOffset = Integer.parseInt(fs.getString("DataBlockOffset"), 16);
            checkBlockOffset = Integer.parseInt(fs.getString("CheckBlockOffset"), 16);
            
            formatError = false;
        }
        catch (Exception e) {
            e.printStackTrace(); // REDFLAG: remove
        }

        return ret;
    }


    public String getMessageName() { return messageName; }
    
    // REDFLAG: required?
    public final void setClose(boolean value) { close = value; }

    ////////////////////////////////////////////////////////////
    public final String getFECAlgorithm() { return algoName; }
    public final long getFileLength() { return fileLength; }
    public final long getOffset() { return offset; } 
    public final int getBlocksRequired() { return blocksRequired; }
    public final int getBlockCount() { return blockCount; }
    public final int getBlockSize() { return blockSize; }
    public final int getCheckBlockCount() { return checkBlockCount; }
    public final int getCheckBlockSize() { return checkBlockSize; }
    public final int getDataBlockOffset() { return dataBlockOffset; }
    public final int getCheckBlockOffset() { return checkBlockOffset; }
    public final int getSegments() { return segments; }
    public final int getSegmentNum() { return segmentNum; }
}






