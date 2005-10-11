package freenet.client.metadata;

import freenet.client.ClientFactory;
import freenet.client.BackgroundInserter;

/**
 * Basically a struct to hold settings information for the metadata parts.
 * For simplicity, we'll just extend this when we need new settings to be 
 * passed (no need to complicate things).
 */

public class MetadataSettings {

    private long time;

    private boolean nonLocal = false;
    private boolean handleSplitFiles = true;
    private int blockHtl = -1;
    private int splitFileRetries = -1;
    private int splitFileRetryHtlIncrement = -1;
    private int healPercentage = 0;
    private int healingHtl = 5;
    
    private int splitFileThreads = -1;
    private ClientFactory clientFactory = null;
    private String splitFileAlgoName = null;
    private boolean doParanoidChecks = true;
    private String checksum = null;
    
    private int maxLog2Size = 0;
    
    private boolean followContainers = true;
    
    private BackgroundInserter inserter = null;

    private boolean randomSegs = false;

    /**
     * Creates a new MetaDataSettings and all values to default.
     */
    public MetadataSettings() {
        time = System.currentTimeMillis();
    }

    /**
     * Sets the metadatas current time.
     * @param time  The time to use as current in metadata translation
     *              in milliseconds of the epoch.
     */
    public void setCurrentTime(long time) {
        this.time = time;
    } 

    /**
     * Returns the time to use as current in metadata translation in 
     * milliseconds of the epoch.
     */
    public long getCurrentTime() {
        return time;
    }

    // REDFLAG: document.
    
    /**
     * When this is set, keys in the local DataStore are ignored.
     * <p>
     * NOTE: The current Fred implementation implements this option
     *       by deleting the key from the local DataStore before making
     *       the request.  This behavior may change in the future.
     **/
    public final void setNonLocal(boolean value) { nonLocal = value; }
    public final void setHandleSplitFiles(boolean value) { handleSplitFiles = value;} 
    public final void setBlockHtl(int value) { blockHtl = value; }
    public final void setSplitFileRetries(int value) {splitFileRetries = value;}
    public final void setSplitFileRetryHtlIncrement(int value) { splitFileRetryHtlIncrement = value; }
    public final void setHealPercentage(int value) { healPercentage = value; }
    public final void setHealingHtl(int value) { healingHtl = value; }
    public final void setSplitFileThreads(int value) { splitFileThreads = value; }
    public final void setClientFactory(ClientFactory factory) { clientFactory = factory; }
    public final void setSplitFileAlgoName(String value) { splitFileAlgoName = value; }
    // Turns on extra integrity checks for SplitFile downloading.
    public final void enableParanoidChecks(boolean value) { doParanoidChecks = value;}
    public final void setMaxLog2Size(int sz) { maxLog2Size = sz; }
    public final void setFollowContainers(boolean f) { followContainers = f; }
    
    public final boolean isNonLocal() { return nonLocal; }
    public final boolean getHandleSplitFiles() { return handleSplitFiles; }
    public final int getBlockHtl() { return blockHtl; }
    public final int getSplitFileRetries() { return splitFileRetries; }
    public final int getSplitFileRetryHtlIncrement() { return splitFileRetryHtlIncrement; }
    public final int getHealPercentage() { return healPercentage; }
    public final int getHealingHtl() { return healingHtl; }
    public final int getSplitFileThreads() { return splitFileThreads; }
    public final String getSplitFileAlgoName() { return splitFileAlgoName; }
    
    public final ClientFactory getClientFactory() { return clientFactory; }
    public final boolean doParanoidChecks() { return doParanoidChecks; }
    
    public final BackgroundInserter getBackgroundInserter() { return inserter; }
    public final void setBackgroundInserter(BackgroundInserter value) { inserter = value; }

    public final boolean getRandomSegs() { return randomSegs; }
    public final void setRandomSegs(boolean value) { randomSegs = value; }

    // For internal use only.  Don't call this from client code. 
    // It won't do what you think it does.
    public final void setChecksum(String value) { checksum = value; }

    public final String getChecksum() { return checksum; }
    public final int getMaxLog2Size() { return maxLog2Size; }
    public final boolean getFollowContainers() { return followContainers; }
}







