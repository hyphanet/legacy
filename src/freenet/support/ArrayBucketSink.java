package freenet.support;


/**
 * A BucketSink implementation which collects the
 * put Buckets in an array.
 **/
public class ArrayBucketSink implements BucketSink {
    public void setLength(int length) {
        buckets = new Bucket[length];
        count = 0;
    }

    public void putBucket(Bucket bucket, int number) {
        if (buckets == null) {
            throw new IllegalStateException("Call setLength() to initialize first!");
        }
        
        if (number < 0 || number >= buckets.length) {
            throw new IllegalArgumentException("Bad index: " + number + 
                                               " (0," + (buckets.length - 1) + ").");
        }
        if (buckets[number] == null) {
            count++;
        }
        else {
            System.err.println("ArrayBucketSink.putBucket -- " + number + " was overwritten!");
        }
        buckets[number] = bucket;
    }

    public Bucket[] getBuckets() {
        Bucket[] ret = new Bucket[buckets.length];
        System.arraycopy(buckets, 0, ret, 0, buckets.length);
        return ret;
    }

    public int getCount() {
        return count;
    }

    private int count = -1;
    private Bucket[] buckets = null;
}







