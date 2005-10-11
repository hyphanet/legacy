/*
 * Created on Jan 17, 2004
 *
 */
package freenet.support;

import java.util.Hashtable;

import freenet.Core;
import freenet.Identity;
import freenet.fs.dir.FileNumber;

/**
 * @author Iakin
 *
 *A class for building FileNumbers from Identity's (or Identitys's + Section-string's)
 *The class caches previously built FileNumbers in order to speed up re-requests
 */
public class CachingFileNumberBuilder {
	
	private CacheEntry ceLast; //Last resolved entry
	private boolean assumeHighIdentityTemporalLocality;
	private boolean assumeHighSectionNameTemporalLocality;
	private final Hashtable cache = new Hashtable(); //Maps from Identity's to CacheEntry's
	private long lSizeWarnThreshold; //An arbitrary cache size at which we should log a warning if the cache size increases even further //TODO: We really ought to remove stuff from the cache instead of warning 
	

	/**
	 * Constructs a new CachingFileNumberBuilder object 
	 * @param lSizeWarnThreshold An arbitrary cache size at which we should log a warning if the cache size increases even further
	 * @param assumeHighIdentityTemporalLocality Optimize Identity-to-FileNumber resolving by assuming High Temporal Locality of the requested Identity's
	 * @param assumeHighSectionNameTemporalLocality Optimize Identity+section-to-FileNumber resolving by assuming High Temporal Locality of the requested sections
	 */
	public CachingFileNumberBuilder(long lSizeWarnThreshold,boolean assumeHighIdentityTemporalLocality,boolean assumeHighSectionNameTemporalLocality){
		this.lSizeWarnThreshold = lSizeWarnThreshold;
		this.assumeHighIdentityTemporalLocality = assumeHighIdentityTemporalLocality;
		this.assumeHighSectionNameTemporalLocality = assumeHighSectionNameTemporalLocality; 
	}

	/**
	 * Builds a FileNumber object from the supplied Identity object
	 * @param i	The identity to construct a FileNumber for
	 * @return the FileNumber representing the supplied Identity
	 */
	public synchronized  FileNumber build(Identity i){
		CacheEntry ce = innerGet(i);
		return ce.primaryfn;
	}

	/**
	 * Builds a FileNumber object from the supplied Identity+section object
	 * @param i 			The identity to use as base in constructing the supplied FileNumber
	 * @param sectionName	Hmmm TODO: Description
	 * @return the FileNumber representing the supplied Identity+section
	 */
	public synchronized  FileNumber build(Identity i,String sectionName){
		CacheEntry ce = innerGet(i);
		return ce.buildSectionFileName(sectionName);
	}
	
	private CacheEntry innerGet(Identity id){
		if(assumeHighIdentityTemporalLocality && ceLast != null && id == ceLast.id) //Try to use the users hint for something    
			return ceLast;
			
		CacheEntry ce =(CacheEntry)cache.get(id);
		if(ce == null){
			ce = new CacheEntry(id);
			cache.put(id,ce);
			if(cache.size() > lSizeWarnThreshold)
				Core.logger.log(this,"Passed warning threshold ("+cache.size()+">"+lSizeWarnThreshold+")",Logger.ERROR); //TODO: Should we try to implement some kind of LRU to know which entry to drop?
		}
		if(assumeHighIdentityTemporalLocality)
			ceLast = ce;
		return ce;
	}
	
	private class CacheEntry {
			private FileNumber primaryfn;
			private Identity id;
			private byte[] fp;			
			private Hashtable sectionsCache; //Maps from section-name (string) to FileNumber
			private FileNumber fnLastSection; //Last resolved section-FileNumber
			private String lastSectionName; //The name of the last resolved section
			CacheEntry(Identity i){
				fp = i.fingerprint();
				id =i;
				primaryfn = new FileNumber(fp);
			}
			
			public FileNumber buildSectionFileName(String sectionName) {
				return innerBuildSectionFileName(fp,sectionName);
			}
			
			private FileNumber innerBuildSectionFileName(byte[] fingerprint, String sectionName) {
				if(assumeHighSectionNameTemporalLocality && fnLastSection != null && sectionName == lastSectionName) //Try to use the users hint for something
					  return fnLastSection;
				FileNumber fn=null;
				if (sectionsCache == null) {
					sectionsCache = new Hashtable();
				}else{
					fn = (FileNumber)sectionsCache.get(sectionName);
				}
				if(fn == null){
					fn = makeFileNumber(fingerprint,sectionName);
					sectionsCache.put(sectionName,fn);
				}
				if(assumeHighSectionNameTemporalLocality){
					lastSectionName = sectionName;
					fnLastSection = fn;
				}
				return fn;
			}
			/**
			 * Make a FileNumber from the byte array resulting from concatenating the Identity fingerprint with the UTF-8
			 * representation of ".<name>"
			 */
			private final FileNumber makeFileNumber(byte[] fingerprint,String name) {
				byte[] nm = UTF8.encode('.' + name);
				byte[] fn = new byte[fingerprint.length + nm.length];
				System.arraycopy(fingerprint, 0, fn, 0, fingerprint.length);
				System.arraycopy(nm, 0, fn, fingerprint.length, nm.length);
				return new FileNumber(fn);
			}
		}
}