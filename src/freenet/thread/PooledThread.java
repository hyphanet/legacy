package freenet.thread;

/**
 * A thread which is a member of a pool
 **/

public interface PooledThread {
  public abstract Runnable job();
}
