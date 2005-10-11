/* -*- Mode: java; c-basic-indent: 4; tab-width: 4 -*- */

package freenet.client.http;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.MalformedURLException;
import java.util.LinkedList;
import java.util.ListIterator;
import java.util.Random;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.onionnetworks.fec.FECCode;
import com.onionnetworks.fec.FECCodeFactory;
import com.onionnetworks.util.Buffer;

import freenet.FieldSet;
import freenet.KeyException;
import freenet.client.AutoRequester;
import freenet.client.Client;
import freenet.client.ClientEvent;
import freenet.client.ClientFactory;
import freenet.client.FreenetURI;
import freenet.client.GetRequest;
import freenet.client.Request;
import freenet.client.listeners.DoneListener;
import freenet.client.metadata.DateRedirect;
import freenet.client.metadata.DocumentCommand;
import freenet.client.metadata.InvalidPartException;
import freenet.client.metadata.Metadata;
import freenet.client.metadata.MetadataPart;
import freenet.client.metadata.MetadataSettings;
import freenet.client.metadata.Redirect;
import freenet.client.metadata.StreamPart;
import freenet.node.Node;
import freenet.support.Bucket;
import freenet.support.Logger;
import freenet.support.io.ReadInputStream;

/**
 * Streaming Audio/Video request servelet
 *
 * <p>NOTE: This is a <b>prototype</b></p>
 * 
 * @author <a href="mailto:fish@artificial-stupidity.net">Jaymz Julian</a>
 */
public class StreamServlet extends FproxyServlet // FIXME!
{
	private boolean firstAccess = true;
	private Node node;
	protected static ClientFactory clientFactory;
	int maxHtl=50;

	class MyDoneListener extends DoneListener {
		public void receive(ClientEvent ce) {
			logger.log(this, "Got event "+ce.getDescription(),
							Logger.DEBUG);
			if(ce instanceof freenet.client.events.ExceptionEvent) {
				Exception e = 
					((freenet.client.events.ExceptionEvent)ce).getException();
				logger.log(this, "Got Exception Event", e,
						   Logger.ERROR);
			}
		}
	}
	
	class RequestBitch
	{
		protected DoneListener dl=null;
		protected Bucket data;
		protected Bucket metadata;
		protected int htl;

		public GetRequest g;
		boolean done;
		public boolean getDataAsync(FreenetURI uri, int htl, Bucket metadata, Bucket data) throws IOException, KeyException
		{
			logger.log(this, "getDataAsync("+uri+","+htl+",,)",
							Logger.DEBUG);
			System.out.println("async requesting: "+uri);
			g=new GetRequest(htl, uri, metadata, data);
			dl = new MyDoneListener();
			g.addEventListener(dl);
			Client c = clientFactory.getClient(g);
			c.start();
			done=false;
			this.data=data;
			this.metadata=metadata;
			this.htl=htl;
			return true;
		}

		public boolean poll() throws IOException, KeyException
		{
			if(g.state()<0)
			{
				done=true;
				return false;
			}
			if(g.state()==Request.DONE)
			{
				// handle redirects
				// FIXME: add DBR handling
				Metadata m=null;
				try {
					m=new Metadata(metadata.getInputStream(), new MetadataSettings());
				} catch (InvalidPartException e)
				{
					done=true;
					return true;
				}
				DocumentCommand d=m.getDefaultDocument();
				MetadataPart p=d.getControlPart();
				if(p==null)
				{
					done=true;
					return true;
				}
				else if(p instanceof DateRedirect)
				{
					metadata.resetWrite();
					DateRedirect z=(DateRedirect)p;
					// why isn't getCurrentTarget() public?!
					FreenetURI target=z.getTargetForTime(z.getTarget(), System.currentTimeMillis() / 1000);
					this.getDataAsync(target, htl, metadata, data);
				}
				// note that this must go AFTER DateRedirect, due to inheritence reasons.  Otherwise, a big
				// scary monster WILL EAT YOU.
				else if(p instanceof Redirect)
				{
					metadata.resetWrite();
					FreenetURI target=((Redirect)p).getTarget();
					this.getDataAsync(target, htl, metadata, data);
				}
				else
				{
					done=true;
				}
			}
			return true;
		}
		
		public boolean getData(FreenetURI uri, int htl, Bucket metadata, Bucket data) throws IOException, KeyException
		{
			getDataAsync(uri, htl, metadata, data);
			while(!this.done)
			{
				this.poll();
				try {
					Thread.sleep(100);
				} catch (InterruptedException e) {}
			}
			if(g.state()==Request.DONE)
				return true;
			else
				return false;
		}

		public FieldSet metadataToFieldSet(Bucket metadata) throws IOException
		{
			ReadInputStream myMetadataStream=new ReadInputStream(metadata.getInputStream());
			while(!myMetadataStream.readToEOF('\n', '\r').equalsIgnoreCase("Document"));
			FieldSet streamMetadata=new FieldSet();
			streamMetadata.parseFields(myMetadataStream);
			return streamMetadata;
		}
		
		public void finalize() {
			try {
				bucketFactory.freeBucket(metadata);
			} catch (IOException e) {
				logger.log(this, "IOException freeing metadata bucket: "+e,
						   e, Logger.ERROR);
			}
			metadata = null;
			try {
				bucketFactory.freeBucket(data);
			} catch (IOException e) {
				logger.log(this, "IOException freeing data bucket: "+e,
						   e, Logger.ERROR);
			}
			data = null;
			if(g != null && (!(g.state() == Request.DONE ||
							   g.state() < Request.INIT))) {
				logger.log(this, "Finalizing BlockRequest with request in "+
						   "state "+g.stateName(), Logger.ERROR);
			}
			g = null;
		}
	}
	
	public void init()
	{
		super.init();
		ServletContext context = getServletContext();
		node = (Node)context.getAttribute("freenet.node.Node");
		context = getServletContext();
		clientFactory = (ClientFactory) context.getAttribute("freenet.client.ClientFactory");
	}

	protected void showGui(PrintWriter pw)
	{
		pw.println("<html><head><title>Freenet media stream retrieval system</title></head>");
		pw.println("<body>");
		pw.println("<form method=GET target=\"/servlet/stream/\">");
		pw.println("This page is horrible.  Someone fix it.  That said, this is the only non-form-your-own-request UI that there is for this, so I guess you need to read the documentation that I wrote at 4am.  D'oh!");
		pw.println("<table>");
		pw.println("<tr><td>Source: </td><td><input size=50 name=\"uri\"></td></tr>");
		pw.println("<tr><td>Hops To Live: </td><td><input size=50 name=\"htl\" value=10></td></tr>");
		pw.println("<tr><td>Buffer Size: </td><td><input size=50 name=\"buffer\" value=5></td></tr>");
		pw.println("<tr><td>Prefetch Probability: </td><td><input size=3 name=\"pprob\" value=10></td></tr>");
		pw.println("<tr><td colspan=2><input type=\"submit\"></td></tr>");
		pw.println("</table>");
		pw.println("</form>");
		pw.println("</body></html>");
	}
	protected void doGet(HttpServletRequest req, HttpServletResponse resp)
		throws IOException
	{
		if(firstAccess)
		{
			init();
			firstAccess=false;
		}
		int myHtl=15;
		String requestString = req.getParameter("uri");
		
		try {
			
			if(requestString != null && requestString.length()==0)
				requestString = null;
			
			if(requestString != null) {
				requestString = freenet.support.URLDecoder.decode(requestString);
			}
			
			if(requestString == null) {
				requestString = req.getRequestURI();
				if(requestString != null) {
					requestString = freenet.support.URLDecoder.decode(requestString);
				}
			}
			
			// chop leading /servlet/stream - FIXME: don't hardcode
			if (requestString != null && requestString.startsWith("/servlet/stream")) {
				requestString = requestString.substring("/servlet/stream".length());
				if(requestString.startsWith("/")) 
					requestString = requestString.substring(1);
			}
			
		} catch (Exception e) {
			PrintWriter pw = resp.getWriter();
			resp.setContentType("text/plain");
			pw.println("freenet.support.URLDecoder.decode threw.  shite.");
			return;
		}
		
		logger.log(this, "Read key from query: " + requestString, Logger.DEBUG);
		
		if(requestString==null || requestString.length()==0) {
			PrintWriter pw = resp.getWriter();
			resp.setContentType("text/html");
			showGui(pw);
			return;
		}
		
		// okay, get the main bit o metadata
		FreenetURI requestUri;
		try {
			requestUri=new FreenetURI(requestString);
		}
		catch (MalformedURLException e)
		{
			//writeErrorMessage(e, resp, null, key, null, htlUsed, null, null, tryNum+1)
			logger.log(this, "Malformed URL Exception", e, Logger.DEBUG);
			writeErrorMessage(e, req,resp, null, requestString, null, myHtl, null, null, null, 0);
			return;
		}

		// Get the user specified queue length
		int queueLength=5;
		String requestQueueLength=req.getParameter("buffer");
		if(requestQueueLength!=null) {
			try {
				queueLength=Integer.parseInt(requestQueueLength);
			} catch (NumberFormatException e) {
				// Use default
			}
		}
		//System.out.println(queueLength);

		int htlStep=5;
		String requestHtlStep=req.getParameter("htlstep");
		if(requestHtlStep!=null)
		{
			try {
				htlStep=Integer.parseInt(requestHtlStep);
			}
			catch (Exception e) {}
		}
		String requestHtl=req.getParameter("htl");
		if(requestHtl!=null)
		{
			try {
				myHtl=Integer.parseInt(requestHtl);
			}
			catch (Exception e) {}
		}

		logger.log(this, "HTL: "+myHtl, Logger.DEBUG);
		
		// Precache Probability is the probability that it will fetch a 
		// random chunk between here and endChunk rather than the next required chunk!
		//
		// 10% seems like a reasonable value to me, but we need to tune this.
		int myPprob=10;
		String requestPprob=req.getParameter("pprob");		
		if(requestPprob!=null)
		{
			try {
				myPprob=Integer.parseInt(requestPprob);
			}
			catch (Exception e) {}
		}

		// Welcome to 'I hate you' theatre, with your 
		// host, dj fish!
		///*File*/Bucket metadata = bucketFactory.makeBucket(-1);
		/*File*/Bucket data = bucketFactory.makeBucket(-1);
		//GetMetaRequestPro cess rp=new GetMetaRequestProcess(requestUri, myHtl, data, new FileBucketFactory(), 0, true, null);
		//Request r;
                //RequestBitch r=new RequestBitch();
		AutoRequester ar = new AutoRequester(clientFactory);
		FailureListener fl = new FailureListener(logger);
		ar.addEventListener(fl);
		if(!ar.doGet(requestUri, data, myHtl))
		{
                        //PrintWriter pw = resp.getWriter();
 			resp.setContentType("text/plain");
			//pw.println("ERROR: Your key didn't work.  try a higher htl or something.");
			//pw.println("Someone should fix the call to writeErrorMessage to display that nice non-threatening freenet error message correctly");
			writeErrorMessage(fl.getException(ar, requestUri,req), req,resp, null, 
							  requestString, null, myHtl, null, null, null, 1);
			return;
		}
		
		Metadata meta = ar.getMetadata();
		if(meta == null) {
			writeShortError(resp, "No metadata");
			return;
		}
		
		StreamPart sp = meta.getStreamPart();
		
		if(sp == null) {
			writeShortError(resp, "No StreamPart, metadata was:\n"+meta.writeString());
			return;
		}
		
		if(sp.revision != 1) {
			writeShortError(resp, "Unrecognized revision "+sp.revision);
			return;
		}
		
		// get the URI
		FreenetURI myUri=sp.uri;
		if(myUri==null)
			{
			myUri=requestUri;
		}
		
		String mimetype=meta.getMimeType("application/ogg");
		
		boolean headerKey = sp.header;

		// don't you love these big try/catch waterfalls, it's so much nicer
		// than Integer,parseInt behaving sanely, yes.
		long myStartChunk = sp.startChunk;
		long myEndChunk = sp.endChunk;
		int chunkSeconds = sp.chunkSeconds;
		
		if(chunkSeconds > 0) {
			int t=queueLength*60;
			queueLength=t/chunkSeconds;
			
			// round up, not down!
			if((queueLength*chunkSeconds)<t)
				queueLength++;
		}
		
		String fecType = sp.fecAlgorithm;
		
		int fecn = sp.blockCount + sp.checkBlockCount;
		int feck = sp.blockCount;
		
		// For some bizzare reason, not setting this *first* will
		// cause my particular JVM to shit itself in a rather unfun
		// way.  AngyMunkey!
		//
		// This being said, on blackdown's 1.3 JVM, which co-incidently is 
		// the ONLY one which will both compile and run fred on my system,
		// reching the end of this function will sig11 the JVM anyhow.
		// I believe this to be sun/blackdown's anti-kiddie-porn filter :-p.
		//
		// So, waht I need, is a way to signal the JVM that this is just audio :-p.
		resp.setStatus(HttpServletResponse.SC_OK);
		resp.setContentType(mimetype);
		OutputStream out = resp.getOutputStream();

		// Get the FEC decoder
		FECCode fecbitch=FECCodeFactory.getDefault().createFECCode(feck, fecn);

		HttpBuffer outputBuffer=new HttpBuffer(queueLength, out);
		LinkedList requestBuffer=new LinkedList();
		
		// fill the head of the queue
		long currentChunk=myStartChunk;
		long blocksBetweenPrefetch=100/myPprob;
		long numpi=0;
		Random notVeryRandom=new Random();
		// get the ASF or other encapsulation format header if it exists (as key 0)
		if(headerKey)
		{
			requestBuffer.add(new StreamChunkRequestor(myUri, 0, fecType, fecn, feck, fecbitch, htlStep, false));
			if(currentChunk==0)
				currentChunk++;
		}
		boolean failedBlock=false;
		// start the ejector
		Thread t=new Thread(outputBuffer, "StreamServlet ejector");
		t.start();
		while(true)
		{
			// check if we need another block
			while(requestBuffer.size()<queueLength && !failedBlock)
			{
				long nextChunk= -1;
				boolean throwaway=false;
				if(myEndChunk>0) {
					// are we on the final chunk?
					// set a flag to make this all end!
					if(currentChunk==myEndChunk) {
						failedBlock=true;
						nextChunk=currentChunk;
						currentChunk++;
					} else {
						// check if we need to prefetch, and fetch a
						// throwaway block if we do
						if(numpi>=blocksBetweenPrefetch) {
							numpi=0;
							throwaway=true;
							int s=(int)(myEndChunk-currentChunk);
							// nextInt(s) isn't allowed in java 1.1
							// anyhow, this doesn't need to be very random
							// just quick and dirty - don't ever do this!
							int fromHere=notVeryRandom.nextInt(30);
							fromHere%=s;
							nextChunk=currentChunk+fromHere;
						} else {
						// otherwise, just get the next block as normal, 
						// and increase numpi
							nextChunk=currentChunk;
							currentChunk++;
							numpi++;
						}
					}
				} else {
					// always use the retrieve the next chunk if 
					// the stream is live
					nextChunk=currentChunk;
					currentChunk++;
				}
				// sanity check of the above code
				if(nextChunk >= 0)
					requestBuffer.add(new StreamChunkRequestor(myUri, nextChunk, fecType, fecn, feck, fecbitch, htlStep, throwaway));
				else
					logger.log(this, "Big scary monsters when adding to buffer!", Logger.ERROR);
			}

			if( !((StreamChunkRequestor)requestBuffer.getFirst()).inProgress &&
			    !((StreamChunkRequestor)requestBuffer.getFirst()).success &&
			    !((StreamChunkRequestor)requestBuffer.getFirst()).throwaway)
			{
				// All hail the great and powerful Angry Monkey
				return;
			}

			// check for completed blocks
			ListIterator l=requestBuffer.listIterator(0);
			while(l.hasNext())
			{
				StreamChunkRequestor j=(StreamChunkRequestor)l.next();
				// update the request object if it needs it
				if(j.inProgress)
					j.poll();

				// check if the block is finished, and
				// if it needs to be moved to the output buffer
				if(!j.inProgress)
				{
					if(j.success)
					{
						if(!j.throwaway)
						{
							outputBuffer.add(j);
							logger.log(this, "Adding chunk #"+myUri+" to outputBuffer",
										Logger.DEBUG);
						}
						l.remove();
					} else {
						logger.log(this, "Chunk #"+myUri+
										" failed, not adding to outputBuffer", 
										Logger.DEBUG);
						failedBlock=true;
					}
				}
			}

			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {}
		}
	}

	class HttpBuffer implements Runnable
	{
		boolean locked;
		public int listLen;
		protected int queueLength;
		protected boolean started;
		protected OutputStream out;
		LinkedList outputBuffer;

		HttpBuffer(int qs, OutputStream o)
		{
			locked=false;
			outputBuffer=new LinkedList();
			listLen=0;
			queueLength=qs;
			started=false;
			out=o;
		}

		public void run()
		{
			while(true)
			{
				while(outputBuffer.size()>=queueLength || 
				     (outputBuffer.size()>0 && started==true))
				{
					while(locked)
						try { Thread.sleep(100); } catch (InterruptedException e) {}
					locked=true;
					started=true;
					try 
					{
						out.write( ((StreamChunkRequestor)outputBuffer.getFirst()).outputData );
					}
					catch (IOException e)
					{
						return;
					}
					outputBuffer.removeFirst();
					listLen=outputBuffer.size();
					locked=false;
				}
				try { Thread.sleep(1000); } catch (InterruptedException e) {}
			}
		}

		public void add(StreamChunkRequestor j)
		{
			while(listLen>queueLength)
				try { Thread.sleep(1000); } catch (InterruptedException e) {}
			while(locked)
				try { Thread.sleep(100); } catch (InterruptedException e) {}
			locked=true;

			outputBuffer.add(j);
			listLen=outputBuffer.size();
			
			locked=false;
		}

		public int size()
		{
			return queueLength;
		}
	}

	class StreamPartRequestor
	{
		public boolean success;
		public boolean inProgress;
		public int finalSize;
		public /*File*/Bucket data;
		public /*File*/Bucket metadata;
		protected RequestBitch r;
		FreenetURI uri;
		int htl;
		FECCode fecbitch;
		int htlStep;
		
		StreamPartRequestor(String myuri, FECCode ifecbitch, int iHtlStep)
			throws IOException {
			try {
				uri=new FreenetURI(myuri);
			} catch (Exception e) {
				System.out.println("Arg! Bad things happened in StreamPartRequestor");
			}
			htl=0;
			success=inProgress=false;
			finalSize=0;
			fecbitch=ifecbitch;
			htlStep=iHtlStep;
			r=null;
			data = bucketFactory.makeBucket(-1);
			metadata = bucketFactory.makeBucket(-1);
		}

		StreamPartRequestor(FreenetURI myuri, FECCode ifecbitch, int iHtlStep)
			throws IOException {
			uri=myuri;
			htl=0;
			success=inProgress=false;
			finalSize=0;
			fecbitch=ifecbitch;
			htlStep=iHtlStep;
			r=null;
			data = bucketFactory.makeBucket(-1);
			metadata = bucketFactory.makeBucket(-1);
		}

		public void poll() throws IOException, KeyException
		{
			if(r==null)
				return;
			if(r.done)
			{
				if(r.g.state()==Request.DONE)
				{
					FieldSet streamMetadata=null;
					try {
						streamMetadata=r.metadataToFieldSet(metadata);
					} catch (Exception e) {}
					//System.out.println(streamMetadata);
					FieldSet streamSpecific=streamMetadata.getSet("Info").getSet("Stream");
					FieldSet fecSpecific=streamSpecific.getSet("fec");
					finalSize=Integer.parseInt(fecSpecific.getString("actualSize"), 16);
					success=true;
					//System.out.println("Retrieved block!  final size is "+finalSize);
					//System.out.println(uri);
				}
				inProgress=false;
			}
			else
			{
				r.poll();
			}
		}

		public void restart()
		{
			inProgress=true;
			htl=htl+htlStep;
			//System.out.println("Requesting "+uri+" at htl="+htl);
			
			r=new RequestBitch();
			try {
				r.getDataAsync(uri, htl, metadata, data);
			} catch (IOException e) {
				inProgress=false;
				return;
			} catch (KeyException e) {
				inProgress=false;
				return;
			}
		}
		
		public void finalize() {
			if(data != null) {
				try {
					bucketFactory.freeBucket(data);
				} catch (IOException e) {
					logger.log(this, "IOException freeing data bucket: "+e,
							   e, Logger.ERROR);
				}
				data = null;
			}
			if(metadata != null) {
				try {
					bucketFactory.freeBucket(metadata);
				} catch (IOException e) {
					logger.log(this, "IOException freeing metadata bucket: "+e,
							   e, Logger.ERROR);
				}
				metadata = null;
			}
			if(r != null) {
				r.finalize();
			}
		}
	}
	
	class StreamChunkRequestor 
	{
		public boolean success;
		public boolean inProgress;
		public boolean throwaway;
		FreenetURI myUri;
		long myChunk;
		String fecType;
		int fecn;
		int feck;
		int htlStep;
		byte [] outputData;
		FECCode fecbitch;
		StreamPartRequestor myRequestors[];

		// welcome to iFreenet!
		StreamChunkRequestor(FreenetURI iUri, long iChunk, String ifecType, int ifecn, 
							 int ifeck, FECCode ifecbitch, int iHtlStep, boolean ithrowaway) throws IOException {
			success=false;
			inProgress=false;

			myUri=iUri;
			myChunk=iChunk;
			fecType=ifecType;
			fecn=ifecn;
			feck=ifeck;
			fecbitch=ifecbitch;
			htlStep=iHtlStep;
			throwaway=ithrowaway;
			
			restart();
			
		}

		public void restart() throws IOException {
			myRequestors=new StreamPartRequestor[fecn];
			inProgress=true;
			
			// request the chunks
			for(int c=0;c<fecn;c++)
			{
				FreenetURI realUri = myUri.setDocName(myUri.getDocName()+"/"+
													  myChunk+"/"+c);
				// Test the the FEC code actully works
				//
				//if(c==2)
				//	realUri=myUri+"/nothing";
				if(myRequestors[c] != null) myRequestors[c].finalize();
				myRequestors[c]=new StreamPartRequestor(realUri, fecbitch, htlStep);
				myRequestors[c].restart();
			}
		}
		
		public void poll() {
			int retrievedBlocks;
			int progressBlocks;
				
			retrievedBlocks=0;
			progressBlocks=0;
			// check on our slaves
			for(int c=0;c<fecn;c++)
			{
				if(myRequestors[c].inProgress==false)
				{
					if(myRequestors[c].success==true)
					{
						retrievedBlocks++;
					}
					else if(myRequestors[c].htl<maxHtl)
					{
						myRequestors[c].restart();
						progressBlocks++;
					}
				} else {
					try {
						myRequestors[c].poll();
						progressBlocks++;
					} catch (IOException e) {
						logger.log(this, "Error polling "+c+": "+e,
								   e, Logger.NORMAL);
						myRequestors[c].success=false;
						myRequestors[c].inProgress=false;
					} catch (KeyException e) {
						logger.log(this, "Error polling "+c+": "+e,
								   e, Logger.NORMAL);
						myRequestors[c].success=false;
						myRequestors[c].inProgress=false;
					}
				}
			}
			
			if(retrievedBlocks>=feck)
			{
				
				// create the structures for the decoder
				Buffer[] landingZone= new Buffer[feck];
				int[] index=new int[feck];
				int lastBlock=0;
				int finalSize=0;

				for(int c=0;(c<fecn && lastBlock<feck);c++)
				{
					if(myRequestors[c].success==true)
					{
						try {
							// nasty, nasty, nasty, nasty dounle hadnling here....
							byte[] b=new byte[(int)myRequestors[c].data.size()];
							myRequestors[c].data.getInputStream().read(b);
							landingZone[lastBlock]=new Buffer(b);
							finalSize=myRequestors[c].finalSize;
							myRequestors[c].finalize();
						} catch (IOException e) {
							logger.log(this, "IOException in decoding: "+e, e,
									   Logger.ERROR);
						}
						index[lastBlock]=c;
						lastBlock++;
					}
				}
				
				fecbitch.decode(landingZone, index);
				logger.log(this, "Decoded segment: "+myUri, Logger.DEBUG);
				
				// I'd prefer to use System.arraycopy() here, but it
				// seems to not quite do what I want, according to the
				// code I am reading.  Arg.
				outputData=new byte[finalSize];
				int off=0;
				for(int c=0;(c<feck && off<finalSize);c++)
				{
					for(int d=0;(d<landingZone[c].b.length && off<finalSize);d++)
					{
						outputData[off]=landingZone[c].b[d];
						off++;
					}
				}

				// the chills that 
				// you spill up my back
				// keep me filled 
				// with satistfaction
				// when we're done
				// satisfaction 
				// oh what's the harm?
				success=true;
				inProgress=false;
			}
			else if(progressBlocks==0)
			{
				// we're out of blocks to process - d'oh!
				inProgress=false;
			}
		}
	}
	
	private void writeShortError(HttpServletResponse resp, String error) 
		throws IOException {
		PrintWriter pw = resp.getWriter();
		resp.setContentType("text/plain");
		pw.println(error);
	}
	
}
