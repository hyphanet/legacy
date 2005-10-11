/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */
package freenet.node.http;
import java.io.IOException;
import javax.servlet.http.*;

/**
 * An Infolet with extra files
 */
public abstract class MultipleFileInfolet extends Infolet {
    public abstract boolean write(String file, HttpServletRequest req, 
								  HttpServletResponse resp) 
		throws IOException;
}
