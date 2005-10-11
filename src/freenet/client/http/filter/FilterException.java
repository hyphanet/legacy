package freenet.client.http.filter;

/**
 * Security filter exception.
 *
 * @author devrandom@hyper.to
 */

public class FilterException extends java.lang.RuntimeException
{
    public String explanation;
    public FilterAnalysis analysis;

    public FilterException(String msg, String explanation, FilterAnalysis analysis)
    {
	super(msg);
	this.explanation = explanation;
	this.analysis = analysis;
    }

}
