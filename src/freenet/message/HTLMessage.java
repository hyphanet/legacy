package freenet.message;

/**
 * A message which has a HTL field
 **/

public interface HTLMessage  {
  public abstract int getHopsToLive();

  /**
   * @param htl
   */
  public abstract void setHopsToLive(int i);
}
