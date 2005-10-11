/*
 * Created on Dec 9, 2003
 *
 */
package freenet.client;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.NumberFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.LinkedList;

import freenet.Core;
import freenet.client.events.DataNotFoundEvent;
import freenet.client.events.RedirectFollowedEvent;
import freenet.client.events.RestartedEvent;
import freenet.client.events.RouteNotFoundEvent;
import freenet.client.events.SegmentCompleteEvent;
import freenet.client.events.StateReachedEvent;
import freenet.client.events.StreamEvent;
import freenet.client.events.TransferCompletedEvent;
import freenet.client.events.TransferEvent;
import freenet.client.events.TransferFailedEvent;
import freenet.client.events.TransferStartedEvent;
import freenet.client.listeners.CollectingEventListener;
import freenet.client.metadata.MetadataPart;
import freenet.client.metadata.Redirect;
import freenet.node.http.infolets.HTMLProgressBar;
import freenet.node.http.infolets.HTMLTransferProgressIcon;
import freenet.support.Bucket;
import freenet.support.Logger;
import freenet.support.servlet.HtmlTemplate;

/**
 * @author Iakin
 *
 * An AutoRequester that statically keeps track of all in-flight requests and 
 * can report current status to HTML
 * If this functionallity isn't desired then the plain AutoRequester can be used instead 
 */
public class inFlightRequestTrackingAutoRequester extends AutoRequester {

	private final int MAX_HISTORYLENGTH = 20;
	private static LinkedList inFlightRequests = new LinkedList();
	private class Listener implements ClientEventListener{
		public CollectingEventListener eventCollector = new CollectingEventListener(50);
		private ClientEvent terminalEvent = null;
		private long terminalEventTimestamp;
		public synchronized void receive(ClientEvent ce) {
			if(ce instanceof TransferCompletedEvent || ce instanceof RouteNotFoundEvent ||ce instanceof DataNotFoundEvent)
			{	
				terminalEvent = ce;
				terminalEventTimestamp = System.currentTimeMillis();
				synchronized(inFlightRequests){
					if(inFlightRequests.size()>MAX_HISTORYLENGTH)
						inFlightRequests.remove(inFlightRequestTrackingAutoRequester.this); //Slow search.. shouldn't matter really
				}
			}
			eventCollector.receive(ce);
			
		}
		
	}
	private final Listener eventCollector = new Listener();
	private FreenetURI key=null;
	private long startedTime;
	
		 
	HtmlTemplate titleBoxTemplate;
	public inFlightRequestTrackingAutoRequester(ClientFactory clientFactory) {
		super(clientFactory);
		try {
			titleBoxTemplate = HtmlTemplate.createTemplate("titleBox.tpl");
		} catch (IOException e) {
			Core.logger.log(this, "Couldn't load templates", e, Logger.NORMAL);
	}
	}
	
	public boolean doGet(FreenetURI key, Bucket data, int htl, boolean justStart) {
		super.addEventListener(eventCollector);
		startedTime = System.currentTimeMillis();
		synchronized(inFlightRequests){
			if(inFlightRequests.size()>MAX_HISTORYLENGTH)
				if(((inFlightRequestTrackingAutoRequester)inFlightRequests.getFirst()).done())
					inFlightRequests.removeFirst();
			
			inFlightRequests.addLast(this);
		}
		this.key = key;
		return super.doGet(key, data, htl, justStart);
	}
	public boolean done()
	{
		return eventCollector.terminalEvent != null;
	}
	
	public void toHtml(PrintWriter ppw) {
		synchronized (eventCollector) {
			NumberFormat nf = NumberFormat.getInstance();
			nf.setMinimumFractionDigits(0);
			nf.setMaximumFractionDigits(2);
			StringWriter sw = new StringWriter();
			PrintWriter pw = new PrintWriter(sw);
			titleBoxTemplate.set("TITLE", key.toString(false));

			if (!done()) {
				pw.println("<font color = black>");
				pw.println("Request started " + nf.format((System.currentTimeMillis() - startedTime) / 1000) + " seconds ago<br />");
			} else {
				boolean failed = !(eventCollector.terminalEvent instanceof TransferCompletedEvent);
				pw.println("Request started at " + new Date(startedTime) + " and " + (failed ? "failed" : "completed") + " after " + nf.format((eventCollector.terminalEventTimestamp - startedTime) / 1000) + " seconds<br />");
				pw.println("</font>");
			}
			Enumeration eEvents = eventCollector.eventCollector.events();
			Enumeration eTimestapms = eventCollector.eventCollector.timestamps();
				boolean bFirstIteration = true;
			pw.println("<TABLE border='0' cellspacing='0' cellpadding='0'>");
			LinkedList lTransfer = null;
			EventSequence seq = new EventSequence();
			while (eEvents.hasMoreElements()) {
				ClientEvent ce = (ClientEvent) eEvents.nextElement();
				Long timestamp = (Long) eTimestapms.nextElement();
				seq.consume(ce);
			}
			pw.print(seq.render());
			pw.println("</TABLE>");
			if(done()){
				boolean failed = !(eventCollector.terminalEvent instanceof TransferCompletedEvent);
				if (failed)
					pw.println("<font color = red>");
					else
					pw.println("<font color = green>");
				pw.println(eventCollector.terminalEvent.getDescription());
			}
			//eEvents = eventCollector.eventCollector.events();
			//while (eEvents.hasMoreElements()) {
			//	Object o = eEvents.nextElement();
			//	if(!(o instanceof StateReachedEvent))
			//		pw.println("<BR>"+o);
			//}

			titleBoxTemplate.set("CONTENT", sw.toString());
			titleBoxTemplate.toHtml(ppw);
		}

				}
	private static class EventSequence {
		private final LinkedList lSequenceItems = new LinkedList();
		private SegmentTransfer lastTransfer = null;
		private int segmentIndex = 0;
		private boolean segmentDone = false;
		
		EventSequence(){
			//System.out.println("Starting new eventsequence");
		}

		private void consume(ClientEvent ce) {
			if (ce instanceof TransferStartedEvent) {
				if(lSequenceItems.size()>0) ((htmlItem)lSequenceItems.getLast()).notifyNextEventArrived();
				segmentIndex=0;
				segmentDone = false;
				lastTransfer = new SegmentTransfer((TransferStartedEvent) ce, segmentIndex);
				lSequenceItems.add(lastTransfer);
				return;
			}
			if (ce instanceof TransferEvent) {
				//checkNewSegment();
				lastTransfer.addTransfered((StreamEvent) ce);
				return;
			}
			if (ce instanceof SegmentCompleteEvent) {
				checkNewSegment(); //New segment within the same transfer
				lastTransfer.complete();
				//segmentDone = true;
				return;
			}
			if (ce instanceof TransferCompletedEvent) {
				lastTransfer.complete();
				//TODO: Complete lEvents.getLast()?
				segmentDone = true;
				return;
			}
			if(ce instanceof RestartedEvent){
				if(lSequenceItems.size()==0) //Now, if we start out with a Restarted.. assume that OtherEvent renders it at least somewhat properly
					lSequenceItems.add(new OtherEvent(ce));
				((htmlItem)lSequenceItems.getLast()).notifyRestartedEventArrived((RestartedEvent)ce);
				return;
			}
			if (ce instanceof StateReachedEvent) //Ignore these bastards
				return;
			if(ce instanceof RouteNotFoundEvent || ce instanceof DataNotFoundEvent || ce instanceof TransferFailedEvent){ //Is this a terminal/failureindicating event?
				if(lSequenceItems.size()>0) //It _is_ possible to start out with a RNF or DNF
					((htmlItem)lSequenceItems.getLast()).notifyFailure();
			} else {
				((htmlItem)lSequenceItems.getLast()).notifyNextEventArrived();
				lSequenceItems.add(new OtherEvent(ce));
			}
		}
				
		private String render() {
			Iterator it = lSequenceItems.iterator();
			String retval = "";
			while (it.hasNext()) {
				retval += "\n<TR><TD><small>" + ((htmlItem) it.next()).toHTML() + "</small></TD></TR>";
			}
			return retval;
		}

		private void checkNewSegment() {
			if (segmentDone){
				segmentIndex++;
				lastTransfer = new SegmentTransfer(lastTransfer.tse, segmentIndex);
				lSequenceItems.add(lastTransfer);
			}
			segmentDone = false;
		}

		private interface htmlItem {
			String toHTML();
			/**
			 * @param event
			 */
			void notifyRestartedEventArrived(RestartedEvent event);
			void notifyFailure();
			void notifyNextEventArrived();
		}

		private static class OtherEvent implements htmlItem {
			private final ClientEvent ce;
			private boolean failure = false;
			private boolean success = false;
			private int restarteds = 0;
			OtherEvent(ClientEvent ce) {
				this.ce = ce;
				//System.out.println("Received a "+ce.getClass().getName());
			}
			public void notifyFailure(){
				failure = true;
			}
			public void notifyNextEventArrived(){
				success = true; //Consider ourself succeeded when the next non-terminal event has arrived 
			}

			public String toHTML() {
				String info = "";
				if (ce instanceof RedirectFollowedEvent){
					MetadataPart p = ((RedirectFollowedEvent)ce).getMetadataPart();
					String s = p.name();
					if(p instanceof Redirect) //It shouldn't really be anything else here
						s += " to '"+((Redirect)p).getTarget().toString(false)+"'";
					if(failure)
						info = "Failed to follow a "+s;
					else if(success)
						info = "Followed a "+s;
					else
						info = "Trying to follow a "+s+"...";
				}else if (ce instanceof RestartedEvent)
					info = ce.getDescription();
				else
					info = ce.getClass().getName();
				
				String img="";
				if(failure)
					img = new HTMLTransferProgressIcon(HTMLTransferProgressIcon.ICONTYPE_FAILURE).render();
				else if(success)
					img = new HTMLTransferProgressIcon(HTMLTransferProgressIcon.ICONTYPE_SUCCESS).render();
				else
					img = new HTMLTransferProgressIcon(HTMLTransferProgressIcon.ICONTYPE_PROGRESS).render();
				String retval ="<TABLE border='0' cellspacing='0' cellpadding='0'><TR><TD>"+img+"</TD><TD><small>"+info+"</small></TD>";
				for(int i =0; i<restarteds;i++){
					retval += "<TD>"+ new HTMLTransferProgressIcon(HTMLTransferProgressIcon.ICONTYPE_REFRESH).render()+"</TD>";
				}
				retval += "</TR></TABLE>";
				return retval;
			}
			public void notifyRestartedEventArrived(RestartedEvent event) {
				restarteds++;
				//TODO: Something more with the actual contents of the event
			}
		}

		private static class SegmentTransfer implements htmlItem {
			private HtmlTemplate relBarTmp, barTmp;

			private long completed = 0;

			private long total;
			private int segmentNumber=0;
			private boolean newSegmentPending = false;

			private final TransferStartedEvent tse;

			private boolean failure = false;

			public SegmentTransfer(TransferStartedEvent tse, int segmentNumber) {
				this.tse = tse;
				setSegment(segmentNumber);
				//System.out.println("Started segmenttransfer for segment #"+segmentNumber+" of length "+total);
				try {
					relBarTmp = HtmlTemplate.createTemplate("relbar.tpl");
					barTmp = HtmlTemplate.createTemplate("bar.tpl");
				} catch (IOException e) {
					Core.logger.log(this, "Couldn't load templates", e, Logger.NORMAL);
				}
			}
			public void notifyFailure(){
				this.failure = true;
			}
			public void notifyNextEventArrived(){
				//Ignore?! 
			}
			public void notifyRestartedEventArrived(RestartedEvent event) {
				//Ignore?!
		}

			public synchronized void addTransfered(StreamEvent ce) {
				checkNewSegment();
				completed += ce.getProgress();
				//System.out.println("Tranfered another "+ce.getProgress()+" bytes ("+completed+"/"+total+")");
	}
			private void checkNewSegment(){
				if(newSegmentPending){
					setSegment(segmentNumber+1);
					newSegmentPending = false;
				}
			}

			public synchronized void complete() {
				checkNewSegment();
				completed = total;
				newSegmentPending = true;
				//System.out.println("Completed transfer of another "+total+" bytes");
			}
			private void setSegment(int index){
				segmentNumber = index;
				completed = 0;
				total = tse.getSegmentLength(Math.min(segmentNumber,tse.getSegmentCount()));
			}
			private String getSegmentName(){
				return segmentNumber == 0 ? "metadata" : "data";
			}

			public synchronized String toHTML() {
				HTMLProgressBar bar = new HTMLProgressBar(completed, total);
				bar.SetWidth(300);

				String img="";
				String info="";
				boolean drawBar = false;
				if(failure){
					img = new HTMLTransferProgressIcon(HTMLTransferProgressIcon.ICONTYPE_FAILURE).render();
					info = "Failed transfering " + total + " bytes of " + getSegmentName();
				}else if(completed == total){
					img = new HTMLTransferProgressIcon(HTMLTransferProgressIcon.ICONTYPE_SUCCESS).render();
					info = "Transfered " + total + " bytes of " + getSegmentName();
				}else{
					img = new HTMLTransferProgressIcon(HTMLTransferProgressIcon.ICONTYPE_TRANSFERING).render();
					info = "Transfering " + total + " bytes of " + getSegmentName()+", "+completed+" bytes done...";
					drawBar = true;
				}
				
				String retval = "<TABLE border='0' cellspacing='0' cellpadding='0'><TR><TD>"+img+"</TD><TD><small>"+info+"</small></TD></TR>";
				if(drawBar)
					retval += "<TR><TD colspan = 2>" + bar.render() + "</TD></TR>";
				retval += "</TR></TABLE>";
				return retval;
			}
		}
	}
	


	public static Enumeration requestsSnapshot(){
		return Collections.enumeration((LinkedList)inFlightRequests.clone()); //Clone to avoid Concurrency issues
	}


}
