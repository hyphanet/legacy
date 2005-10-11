/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */

package freenet.client.http;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedList;
import java.util.ListIterator;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.onionnetworks.fec.FECCode;
import com.onionnetworks.fec.FECCodeFactory;
import com.onionnetworks.util.Buffer;

import freenet.Core;
import freenet.KeyException;
import freenet.client.Client;
import freenet.client.ClientEvent;
import freenet.client.ClientEventListener;
import freenet.client.ClientFactory;
import freenet.client.ClientKey;
import freenet.client.ClientSSK;
import freenet.client.FreenetURI;
import freenet.client.PutRequest;
import freenet.client.Request;
import freenet.client.events.GeneratedURIEvent;
import freenet.client.listeners.DoneListener;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InfoPart;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.StreamPart;
import freenet.node.Node;
import freenet.support.Bucket;
import freenet.support.Logger;
import freenet.support.NullBucket;

/** 
 * Streaming Audio/Video insert servelet
 * Insert crude insert joke here
 *
 * <p>NOTE: This is a <b>prototype</b></p>
 * 
 * @author <a href="mailto:fish@artificial-stupidity.net">Jaymz Julian</a>
 */
public class StreamInsertServlet extends FproxyServlet {
	
	private boolean firstAccess = true;
	private Node node;
	protected static ClientFactory clientFactory;
	protected static final int staticStream=1;
	protected static final int liveStream=2;
	
	public void init()
	{
		super.init();
		ServletContext context = getServletContext();
		node = (Node)context.getAttribute("freenet.node.Node");
		context = getServletContext();
		clientFactory = (ClientFactory) context.getAttribute("freenet.client.ClientFactory");
	}

	protected class BlockRequest
	{
		protected class MyListener implements ClientEventListener {
			public void receive(ClientEvent ce) {
				if(!running) return;
				logger.log(this, "Got "+ce+" for "+uri,
						   Logger.DEBUG);
				if(ce instanceof GeneratedURIEvent)
					generatedURI = ((GeneratedURIEvent)ce).getURI();
			}
		}
		
		protected Bucket metadata;
		protected Bucket data;
		public FreenetURI uri;
		public FreenetURI generatedURI;
		protected int totalSize;
		protected int htl;
		public PutRequest r;
		PrintWriter pw;
		boolean needMore=false;
		public int block;
		protected ClientEventListener myListener;

		public synchronized void finalize() {
			if(metadata != null) {
				try {
					bucketFactory.freeBucket(metadata);
				} catch (IOException e) {
					logger.log(this, "IOException freeing metadata bucket: "+e,
							   e, Logger.ERROR);
				}
				metadata = null;
			}
			if(data != null) {
				try {
					bucketFactory.freeBucket(data);
				} catch (IOException e) {
					logger.log(this, "IOException freeing data bucket: "+e,
							   e, Logger.ERROR);
				}
				data = null;
			}
			if(r != null && (!(r.state() == Request.DONE ||
							   r.state() < Request.INIT))) {
				logger.log(this, "Finalizing BlockRequest with request in "+
						   "state "+r.stateName(), Logger.ERROR);
			}
			r = null;
		}
		
		public void more()
		{
			// create the redirect for the CHK that we installed
			data=new NullBucket();
			FreenetURI target=r.getURI();
			// FIXME: Bob the angry flower says WRONG WRONG WRONG WRONG WRONG WHERE DID YOU LEARN THAT?! WRONG!
			// I need to make this use the Metadata infrastructure, but I don't actully understand
			// how it works :).
			// 
			// FIXME: okay, I now sorta um kinda understand how it works, I just don't like it :)
			String mstring="Version\nRevision=1\nEndPart\n";
			mstring=mstring+"Document\n";
			mstring=mstring+"Redirect.Target="+target+"\n";
			mstring=mstring+"End\n";
			try {
				metadata=bucketFactory.makeBucket(1024);
				OutputStreamWriter w=new OutputStreamWriter(metadata.getOutputStream());
				w.write(mstring);
				w.flush();
				w.close();
			} catch (Exception e) {
				pw.println("Your stream is about to go to shit - bad things happened in BlockRequest.more(): "+e);
			}
			// trigger the rest of the insert :)
			restart();
			needMore=false;
		}

		public void restart()
		{
			if( (data.size()+metadata.size()) >32700)
			{
				pw.println("Starting insert for "+uri+" to a CHK");
				try {
					r=new PutRequest(htl, new FreenetURI("CHK@"), null, metadata, data);
					r.addEventListener(myListener);
					Client c=clientFactory.getClient(r);	
					c.start();
				} catch (Exception e) {
					pw.println("Bad things happened in BlockRequest.restart(CHK@) - they shouldn't!: "+e);
					e.printStackTrace(pw);
				}
				needMore=true;
			}
			else
			{
				pw.println("Starting insert for "+uri);
				try {
					r=new PutRequest(htl, uri, null, metadata, data);
					Client c=clientFactory.getClient(r);
					c.start();
				} catch (Exception e) {
					pw.println("Bad things happened in BlockRequest.restart(uri@) - they shouldn't!");
				}
			}
			restartedTime = System.currentTimeMillis();
		}
		
		long constructedTime;
		long restartedTime;
		
		public BlockRequest(FreenetURI iuri, Buffer idata, int itotalSize, int ihtl, int blockNum, PrintWriter ipw) throws IOException {
			pw=ipw;
			totalSize=itotalSize;
			htl=ihtl;
			uri=iuri;
			block=blockNum;
			data=bucketFactory.makeBucket(idata.len);
			try {
				logger.log(this, "About to write "+idata.len+" bytes to "+
						   idata, Logger.DEBUG);
				OutputStream os = data.getOutputStream();
				logger.log(this, "Writing "+idata.len+" bytes to "+os,
						   Logger.DEBUG);
				os.write(idata.b, idata.off, idata.len);
				os.close();
			} catch (Exception e) {
				pw.println("Hrm, this isn't good - an exception when trying to convert the buffer to a bucket in BlockRequest(): "+e);
				e.printStackTrace(pw);
			}
			logger.log(this, "Wrote "+idata.len+" bytes to "+data,
					   Logger.DEBUG);
			pw.println("Wrote "+idata.len+" bytes to bucket");
			String mymeta="Version\nRevision=1\nEndPart\nDocument\n";
			mymeta=mymeta+"Info.Stream.fec.actualSize="+Integer.toString(itotalSize, 16)+"\n";
			mymeta=mymeta+"End\n";
			//String mymeta="Info.Stream.fec.actualSize="+Integer.toString(itotalSize, 16)+"\n";
			
			// Another ZIM moment!
			try {
				metadata=bucketFactory.makeBucket(1024);
				OutputStreamWriter w=new OutputStreamWriter(metadata.getOutputStream());
				w.write(mymeta);
				w.flush();
				w.close();
			} catch (Exception e) {
				pw.println("Your stream is about to go to shit - bad things happened in BlockRequest.BlockRequest()");
			}
			myListener = new MyListener();
			
			constructedTime = System.currentTimeMillis();
			restartedTime = constructedTime;
			
			restart();
		}
	}
	
	
	protected void showGui(PrintWriter pw)
	{
		pw.println("<html><head><title>Freenet media stream insertion system</title></head>");
		pw.println("<body>");
		pw.println("<form method=GET target=\"/servlet/streamInsert/\">");
		pw.println("This page is horrible.  Someone fix it.  That said, this is the only non-form-your-own-request UI that there is for this, so I guess you need to read the documentation that I wrote at 4am.  D'oh!");
		pw.println("<table>");
		pw.println("<tr><td>Destination: </td><td><input size=50 name=\"uri\"></td></tr>");
		pw.println("<tr><td>Public Dest: </td><td><input size=50 name=\"public\"></td></tr>");
		pw.println("<tr><td>Source URL: </td><td><input size=50 name=\"url\"></td></tr>");
		pw.println("<tr><td>Hops To Live: </td><td><input size=50 name=\"htl\" value=10></td></tr>");
		pw.println("<tr><td>Block Size: </td><td><input size=50 name=\"blocksize\" value=128000></td></tr>");
		pw.println("<tr><td>Buffer Size (max requests): </td><td><input size=50 name=\"buffer\" value=10></td></tr>");
		pw.println("<tr><td>Client Lag: </td><td><input size=50 name=\"lag\" value=20></td></tr>");
		pw.println("<tr><td>Start Offset: </td><td><input size=50 name=\"offset\" value=0></td></tr>");
		pw.println("<tr><td>Mime type: </td><td><input size=50 name=\"mime\" value=\"audio/ogg\"></td></tr>"); // This is Freenet, how likely are they to use MP3?
		pw.println("<tr><td>Stream type: </td><td><select name=\"type\"><option value=\"live\">Live Streaming</option><option value=\"static\">Static archived stream</option></select>");
		pw.println("<tr><td>Reconnect on timeout (only applies to live streams):<select name=\"reconnect\"><option value=\"true\">Yes</option><option value=\"false\">No</option></select>");
		pw.println("<tr><td colspan=2><input type=\"submit\"></td></tr>");
		pw.println("</table>");
		pw.println("</form>");
		pw.println("</body></html>");
	}

	boolean running = false;

	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
		throws IOException
	{
		running = true;
		try {
			if(firstAccess) {
				init();
				firstAccess=false;
			}
			int myHtl=15;
			int streamType=liveStream;
			
			String requestString=req.getParameter("uri");
			if(requestString==null) {
				resp.setContentType("text/html");
				PrintWriter pw = resp.getWriter();
				showGui(pw);
				return;
			}
			resp.setContentType("text/plain");
			PrintWriter pw = resp.getWriter();
			
			// okay, get the main bit o metadata
			FreenetURI requestUri;
			try {
				requestUri=new FreenetURI(requestString);
			}
			catch (MalformedURLException e) {
				//writeErrorMessage(e, resp, null, key, null, htlUsed, null, null, tryNum+1)
				writeErrorMessage(e, req,resp, null, requestString, null, myHtl, null, null, null, 0);
				return;
			}
			
			// Get the user specified queue length
			int queueLength=3;
			String requestQueueLength=req.getParameter("buffer");
			if(requestQueueLength!=null) {
				try {
					queueLength=Integer.parseInt(requestQueueLength);
				}
				catch (Exception e) {}
			}
			
			int chkSize=32000;
			boolean autoReconnect = false;
			String requestChkSize=req.getParameter("blocksize");
			if(requestChkSize!=null) {
				try {
					chkSize=Integer.parseInt(requestChkSize);
				}
				catch (Exception e) {}
			}
			//pw.println(queueLength);
			
			String requestHtl=req.getParameter("htl");
			if(requestHtl!=null) {
				try {
					myHtl=Integer.parseInt(requestHtl);
				}
				catch (Exception e) {}
			}
			
			String requestType=req.getParameter("type");
			if(requestType!=null && requestType.equalsIgnoreCase("static"))
				streamType=staticStream;
			
			String reconnectAsString = req.getParameter("reconnect");
			if(reconnectAsString != null && 
			   (reconnectAsString.equalsIgnoreCase("true") || 
				(reconnectAsString.equalsIgnoreCase("yes"))))
				autoReconnect = true;
			
			String mimeType=req.getParameter("mime");
			if(mimeType==null) {
				mimeType="application/ogg";
			}
			
			// set the default DBR update to 10 minutes
			int dbrTime=10*60;
			// Note that in HTML, this is specified in minutes
			String requestDbr=req.getParameter("dbr");
			if(requestDbr!=null) {
				try {
					dbrTime=Integer.parseInt(requestDbr);
					dbrTime*=60;
				}
				catch (Exception e) {}
			}
			
			String requestUrl=req.getParameter("url");
			pw.println("Requesting: "+requestUrl);
			pw.println("Htl: "+myHtl);
			resp.flushBuffer();
			
			int myBlockSize=chkSize*4;
			int fecn=6;
			int feck=4;
			
			FreenetURI publicUri=null;
			String requestPubUri=req.getParameter("public");
			if(requestPubUri.length()==0) requestPubUri = null;
			if(requestPubUri!=null)	{
				publicUri=new FreenetURI(requestPubUri);
			}
			if(requestUri.getKeyType().equalsIgnoreCase("SSK") && publicUri==null &&
			   streamType==liveStream) {
				try {
					ClientKey ssk = ClientSSK.createFromInsertURI(null, requestUri);
					publicUri = ssk.getURI();
					pw.println("Private key "+requestUri+" -> Public key "+publicUri);
					// Resulting in SOS condition.  Critical Result: HAPPY! 
					// 			pw.println("I'm really not very clever yet... specifically, I can't actully guess your public key yet");
					// 			pw.println("Someone should implement that.... anyhow, until then, specify the public URI as well as the");
					// 			pw.println("private one...");
					// 			return;
				} catch (KeyException e) {
					pw.println("Key error: "+e);
					e.printStackTrace(pw);
					logger.log(this, "Key error: "+e, Logger.MINOR);
					return;
				}
			}
			
			if(publicUri==null)
				publicUri=requestUri;
			pw.println("Public URI: "+publicUri);
			pw.println("Private URI: "+requestUri);
			PutRequest q;
			
			if(streamType==liveStream)
				q=insertDbr(requestUri, myHtl, dbrTime, publicUri, pw);
			else
				q=insertMetadata(requestUri, myHtl, null, feck, fecn, 0, streamType, mimeType, pw);
			while(q.state()>=0 && q.state()!=Request.DONE) {
				pw.println(Request.nameOf(q.state()));
				pw.flush();
				try {
					Thread.sleep(1000);
				} catch (Exception e) {}
			}
			if(q.state()<0) {
				pw.println(Request.nameOf(q.state()));
				pw.println("----------------------------------");
				pw.println("WARNING: Metadata insert failed!\n");
				pw.println("----------------------------------");
				pw.println("This may be normal if you're inserting");
				pw.println("a live stream that you started");
				pw.println("before with an offset.  But otherwise, ");
				pw.println("things may not work!  Think about it. ");
				pw.println("and/or get someone to fix the code to");
				pw.println("more robust in this situation");
				pw.println("----------------------------------");
				resp.flushBuffer();
				//return;
			} else {
				pw.println("Done Metadata Insert\n");
				resp.flushBuffer();
			}
			
			long lastDBR=0;
			int lag=20;
			String requestLag=req.getParameter("lag");
			if(requestLag!=null) {
				try {
					lag=Integer.parseInt(requestLag);
				} catch (Exception e) {}
			}
			
			
			FECCode fecbitch=FECCodeFactory.getDefault().createFECCode(feck, fecn);
			
			boolean stillActive=true;
			boolean moreBlocks=true;
			int blockNum=0;
			int insertQueueLen=fecn*queueLength;
			LinkedList BlockQueue=new LinkedList();
			PutRequest dbrRequest=null;
			PutRequest mainDbr=null;
			
			// Open HTTP source
			URL myUrl=new URL(requestUrl);
			boolean restart = false;
			int sleepTime = 1000;
			InputStream myIs = null;
			do {
				pw.println("Trying to open stream source: "+requestUrl);
				try {
					myIs=myUrl.openStream();
				} catch (IOException e) {
					Core.logger.log(this, "Aborting StreamInsertServlet due to "+
									"IOException: "+e, e, Logger.ERROR);
					pw.println("Aborting stream insert due to IOException: "+e);
					e.printStackTrace(pw);
					if(autoReconnect) {
						restart = true;
							pw.println("Connection failed. Sleeping "+sleepTime/1000+
									   " seconds before retry.");
						safeSleep(sleepTime);
							pw.println("Retrying "+myUrl);
					} else return;
				}
			} while (restart);
			myIs = new BufferedInputStream(myIs, /*FIXME*/1<<20);

			DoneListener megaListener=new DoneListener();
			
			while(stillActive) {
				// check if we need a new DBR target
				// get the time - note the '+1' for one
				// chunk into the future :)
				//
				// Also, we reinsert the main DBR in order to make sure that it's on the network
				long currentTime=(System.currentTimeMillis() / 1000);
				long reqTime=dbrTime * (( currentTime / dbrTime) +1 );
				if(reqTime!=lastDBR && dbrRequest==null && streamType==liveStream) {
					// get which chunk to report
					int reportChunk=0;
					if(BlockQueue.size()>0) {
						BlockRequest j=(BlockRequest)(BlockQueue.getFirst());
						reportChunk=j.block-lag;
						if(reportChunk<0) 
							reportChunk=0;
					}
					// we need a new DBR - start the request
					FreenetURI DbrUri=requestUri.setDocName(Long.toString(reqTime, 16)+"-"+requestUri.getDocName());
					pw.println("Inserting dbrish metadata at "+DbrUri);
					dbrRequest=insertMetadata(DbrUri, myHtl, null, feck, fecn, reportChunk, streamType, mimeType, pw);
					mainDbr=insertDbr(requestUri, myHtl, dbrTime, publicUri, pw);
				}
				
				if(dbrRequest!=null) {
					FreenetURI uri = dbrRequest.getURI();
					String suri = uri == null ? "(null)" : uri.toString();
					if(dbrRequest.state()<0) {
						// FIXME: Give some reason.
						pw.println("dbrish Metadata insert failed!: "+suri);
						pw.println(Request.nameOf(dbrRequest.state()));
						resp.flushBuffer();
						// 					dbrRequest.finalize();
						dbrRequest=null;
					} else if(dbrRequest.state()==Request.DONE) {
						pw.println("dbrish metadata insert done: "+suri);
						resp.flushBuffer();
						// 					dbrRequest.finalize();
						dbrRequest=null;
						lastDBR=reqTime;
					}
				}
				if(mainDbr!=null) {
					if(mainDbr.state()<0) {
						resp.flushBuffer();
						// 					mainDbr.finalize();
						mainDbr=null;
					} else if(mainDbr.state()==Request.DONE) {
						pw.println("*** main DBR reinserted");
						resp.flushBuffer();
						// 					mainDbr.finalize();
						mainDbr=null;
					}
				}
				// more blocks needed?
				if(moreBlocks && BlockQueue.size()<(insertQueueLen-fecn)) {
					pw.println("Starting retrieve for block #"+blockNum);
					pw.flush();
					byte[] workingBlock=new byte[myBlockSize];
					// read a block from the data input stream
					int thisBlockSize=0;
					int tt=0;
					while(thisBlockSize<myBlockSize) {
						try {
							tt=myIs.read(workingBlock, thisBlockSize, 
										 myBlockSize-thisBlockSize);
						} catch (IOException e) {
							pw.println("IOException reading from "+requestUrl+
									   ", assuming failure: "+e);
							tt = -1;
						}
						if(tt > 0) thisBlockSize+=tt;
						else {
							restart = false;
							sleepTime = 1000;
							do {
								pw.println("Trying to reopen "+requestUrl);
								try {
									myIs=myUrl.openStream();
								} catch (IOException e) {
									logger.log(this, "StreamInsertServlet fetching source "+
											   "stream failed due to IOException: "+e, e, 
											   Logger.ERROR);
									pw.println("Fetching source stream due to "+
											   "IOException: "+e);
									pw.flush();
									if(autoReconnect) {
										restart = true;
										pw.println("Sleeping "+sleepTime/1000+
												   " seconds before retrying");
										pw.flush();
										safeSleep(sleepTime);
										sleepTime *= 2;
									} else break;
								}
							} while (restart);
							myIs = new BufferedInputStream(myIs, /*FIXME*/1<<20);
							pw.println("Reopened "+myUrl);
							pw.flush();
						}
						/*pw.println("Read "+tt+" now got "+thisBlockSize+
						  "; available = "+myIs.available()+"; want: "+
						  myBlockSize);
						  pw.flush();*/
					}
					pw.println("Read "+thisBlockSize+" bytes from remote host");
					pw.flush();
					if(thisBlockSize>0) {
						int myChkSize=thisBlockSize/4;
						// FEC encode this into 6 segments blah blah blah etc
						Buffer[] takeoff=new Buffer[feck];
						Buffer[] landing=new Buffer[fecn-feck];
						int[] index=new int[fecn-feck];
						for(int c=0;c<feck;c++)
							takeoff[c]=new Buffer(workingBlock, c*myChkSize, myChkSize);
						for(int c=0;c<(fecn-feck);c++) {
							landing[c]=new Buffer(new byte[myChkSize]);
							index[c]=c+feck;
						}
						fecbitch.encode(takeoff, landing, index);
						
						for(int c=0;c<feck;c++) {
							FreenetURI myUri=new FreenetURI(requestUri+"/"+
															Integer.toString(blockNum)+"/"+
															Integer.toString(c));
							BlockRequest j=new BlockRequest(myUri, takeoff[c], thisBlockSize, myHtl, blockNum, pw);
							j.r.addEventListener(megaListener);
							BlockQueue.add(j);
						}
						for(int c=0;c<(fecn-feck);c++) {
							FreenetURI myUri=new FreenetURI(requestUri+"/"+
															Integer.toString(blockNum)+"/"+
															Integer.toString(c+feck));
							BlockRequest j=new BlockRequest(myUri, landing[c], thisBlockSize, myHtl, blockNum, pw);
							j.r.addEventListener(megaListener);
							BlockQueue.add(j);
						}
						blockNum++;
					} else {
						pw.println("There is no block #"+blockNum);
						pw.flush();
						moreBlocks=false;
					}
				} else {
					pw.println("Don't need any more blocks: moreBlocks = "+moreBlocks+
							   ", BlockQueue.size() = "+BlockQueue.size()+
							   ", insertQueueLen-fecn = "+insertQueueLen+" - "+fecn+
							   " = "+(insertQueueLen-fecn));
				}
				
				ListIterator l=BlockQueue.listIterator(0);
				pw.println("Items in queue: "+BlockQueue.size());
				pw.flush();

				// if our buffers are full, wait for enough events to happen
				// that we can actully *do* something - note that this isn't
				// nessesarily the best way to do this, because if
				// time per block is greater than DBR time, then some 
				// DBRs will get missed... 
				if(BlockQueue.size()>=(insertQueueLen-fecn)) {
					pw.println("Buffer full... waiting...");
					megaListener.strongWait();
					megaListener.clearState();
				}

				while(l.hasNext()) {
					BlockRequest j=(BlockRequest)l.next();
					if(j.r.state()<0) {
						pw.println(j.uri+" failed to insert!");
						j.finalize();
						l.remove();
					} else if(j.r.state()==Request.DONE) {
						if(j.needMore) {
							pw.println(j.uri+": inserted data, inserting redirect");
							j.more();
						} else {
							pw.println(j.uri+" inserted successfully!!");
							j.finalize();
							l.remove();
						}
					} else if(j.r.state()==Request.INIT) {
						pw.println(j.uri+": something went hairy, stuck in INIT state - restarting!");
						j.restart();
					} else {
						long x = System.currentTimeMillis();
						pw.println(j.uri+": "+Request.nameOf(j.r.state())+" for "+
								   ((x-j.restartedTime)/1000)+", started "+
								   ((x-j.constructedTime)/1000));
					}
				}

				megaListener.clearState();
				pw.flush();
				resp.flushBuffer();
				
				// Don't be a greedy mofo
				// Now that we're stringWait()-ing, this
				// probably should be removed
				try {
					Thread.sleep(1000);
				} catch (Exception e) {}

				if(moreBlocks==false && BlockQueue.size()==0)
					stillActive=false;
			}
			
			// insert the first set of blocks
		} finally {
			running = false;
		}
	}
	
	
	/**
	 * @param sleepTime
	 */
	private void safeSleep(int sleepTime) {
		long now = System.currentTimeMillis();
		long end = now + sleepTime;
		while(now < end) {
			try {
				Thread.sleep(end - now);
			} catch (InterruptedException e) {
				// Ignore
			}
			now = System.currentTimeMillis();
		}
	}


	PutRequest insertDbr(FreenetURI destUri, int htl, int time, FreenetURI publicUri, PrintWriter pw) {
		pw.println("Inserting dbr to "+publicUri+" at "+destUri);
		String mymeta="Version\nRevision=1\nEndPart\nDocument\n";
		mymeta=mymeta+"DateRedirect.Target="+publicUri+"\n";
		mymeta=mymeta+"DateRedirect.Increment="+Integer.toString(time, 16)+"\n";
		mymeta=mymeta+"End\n";
		
		// FIXME: using this is stupid!
		// Your methods are stupid!
		// Your progress has been stupid!
		// Your intelligence is stupid!
		// For the sake of the mission, you must be terminated!
		// Stupidity is the enemy!
		// Zim is the enemy!
		// 
		// Hi floor, make me a sandwich!
		PutRequest j;
		Client c;
		try {
			Bucket metab=bucketFactory.makeBucket(1024);
			OutputStreamWriter w=new OutputStreamWriter(metab.getOutputStream());
			w.write(mymeta);
			w.flush();
			w.close();
			j=new PutRequest(htl, destUri, null, metab, new NullBucket());
			c=clientFactory.getClient(j);
			c.start();
		} catch (Exception e)
		{
			pw.println("Inserting the DBR metadata went hairy!  oops!");	
			return null;
		}
		return j;
	}

	PutRequest insertMetadata(FreenetURI destUri, int htl, FreenetURI streamUri,
							  int feck, int fecn, int startChunk, int streamType, 
							  String mimeType, PrintWriter pw) {
		MetadataSettings ms = new MetadataSettings();
		Metadata meta = new Metadata(ms);
		DocumentCommand dc = new DocumentCommand(ms);
		meta.addCommand(dc);
		InfoPart info = new InfoPart("Stream", mimeType);
		
		try {
			dc.addPart(info);
		} catch (InvalidPartException e) {
			Core.logger.log(this, "Invalid part exception adding info", e,
							Logger.ERROR);
		}
		
		StreamPart st = new StreamPart(1, (streamType!=staticStream), "OnionFEC_a_1_2",
									   feck, (fecn-feck), startChunk, -1, -1, false,
									   streamUri);
		
		try {
			dc.addPart(st);
		} catch (InvalidPartException e) {
			Core.logger.log(this, "Invalid part exception adding stream", e,
							Logger.ERROR);
		}
		
		String mymeta = meta.writeString();
		Core.logger.log(this, "Inserting: "+mymeta, Logger.DEBUG);
// 		mymeta="Version\nRevision=1\nEndPart\nDocument\n";
// 		mymeta=mymeta+"Info.Stream.Format=fproxy\n";
// 		if(streamUri!=null)
// 			mymeta=mymeta+"Info.Stream.uri="+streamUri+"\n";
// 		if(streamType==staticStream)
// 			mymeta=mymeta+"Info.Stream.Type=static\n";
// 		else
// 			mymeta=mymeta+"Info.Stream.Type=live\n";
// 		mymeta=mymeta+"Info.Stream.Version=2.2\n";
// 		mymeta=mymeta+"Info.Stream.fecType=1\n";
// 		mymeta=mymeta+"Info.Stream.fec.k="+Integer.toString(feck, 16)+"\n";
// 		mymeta=mymeta+"Info.Stream.fec.n="+Integer.toString(fecn, 16)+"\n";
// 		mymeta=mymeta+"Info.Stream.StartChunk="+Integer.toString(startChunk, 16)+"\n";
// 		mymeta=mymeta+"Info.Stream.type="+mimeType+"\n";
// 		mymeta=mymeta+"End\n";

		pw.println(destUri);

		// FIXME: using this is stupid!
		// Your methods are stupid!
		// Your progress has been stupid!
		// Your intelligence is stupid!
		// For the sake of the mission, you must be terminated!
		// Stupidity is the enemy!
		// Zim is the enemy!
		// 
		// Hi floor, make me a sandwich!
		PutRequest j;
		Client c;
		Bucket metab = null;
		try {
			metab=bucketFactory.makeBucket(1024);
			OutputStreamWriter w=new OutputStreamWriter(metab.getOutputStream());
			w.write(mymeta);
			w.flush();
			w.close();
			j=new PutRequest(htl, destUri, null, metab, new NullBucket());
			c=clientFactory.getClient(j);
			c.start();
		} catch (Exception e) {
			if(metab != null) {
				try {
					bucketFactory.freeBucket(metab);
				} catch (IOException ex) {
					pw.println("Something screwed up freeing metadata bucket: "+ex);
					ex.printStackTrace(pw);
					logger.log(this, "IOException freeing metadata bucket", ex,
							   Logger.DEBUG);
				}
			}
			pw.println("Inserting the metadata went hairy!  oops!");	
			return null;
		}

		return j;

	}
}
