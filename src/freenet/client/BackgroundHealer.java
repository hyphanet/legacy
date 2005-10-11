package freenet.client;

import freenet.support.*;

/**
 * This interface represents a background process 
 * that attempts to inserts blocks.
 * <p>
 * I does not guarantee that the blocks will actually be inserted.
 * <p>
 * For example, if the BackgroundHealer gets more requests than it can
 * handle, some requests may be abandoned.
 * <p>
 * @author giannij
 **/

interface BackgroundHealer {
    /**
     * Queue a block for insertion.
     * @param block the block to insert.
     * @param owner the BucketFactory to use to free the block.
     * @param htl the insertion htl.
     * @param cipher the cipher to use when inserting.
     **/
    void queue(Bucket block, BucketFactory owner, int htl, String cipher);
}
