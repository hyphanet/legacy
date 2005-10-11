package freenet.client.http.filter;
import freenet.support.BucketFactory;

/**
 * Security filter factory.
 *
 * @author devrandom@hyper.to
 */

public class ContentFilterFactory
{
    public static ContentFilter newInstance(String passthroughMimeTypesString, BucketFactory bf) {
	return new SaferFilter(passthroughMimeTypesString, bf);
    }
}
