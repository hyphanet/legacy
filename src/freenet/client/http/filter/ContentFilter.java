package freenet.client.http.filter;
import java.io.IOException;

import freenet.support.Bucket;

/**
 * Security filter interface.
 *
 * @author devrandom@hyper.to
 */

public interface ContentFilter
{
  public Bucket run(Bucket bucket, String mimeType, String charset)
      throws IOException;
  
  public boolean wantFilter(String mimeType, String charset);
} 
