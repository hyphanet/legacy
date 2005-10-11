package freenet.client.metadata;
import java.io.InputStream;
/**
 * Interface for parts that can have trailing fields, so as to not made it
 * quite so aribtrary.
 *
 * @author oskar
 */
public interface TrailingFieldPart  {

    /**
     * Whether the instance has or accepts a trailing field.
     */
    public boolean hasTrailing(); 

    /**
     * Set the trailing field. May throw and InvalidPartException if
     * hasTrailing() returns false.
     */
    public void setTrailing(InputStream in) throws InvalidPartException;

    /**
     * Get the trailing field if set.
     */
    public InputStream getTrailing();

}
