package freenet.support;
import java.io.IOException;
import freenet.support.Bucket;


/**
 * Interface for an object that processes buckets.
 * There is no guarantee that buckets will be put
 * in any particular order.
 **/
public interface BucketSink {
    void putBucket(Bucket bucket, int number) throws IOException;
}

