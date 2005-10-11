package freenet;

import freenet.node.NodeReference;

/**
 * @author Iakin
 *
 * Utility for sorting ConnectionHandlers
 * */
		public class ConnectionHandlerComparator implements java.util.Comparator 
		{
			public static final int UNORDERED = 0;
			public static final int PEER_ADDRESS = 1;
			public static final int PEER_IDENTITY = 2;
			public static final int SENDING_COUNT = 3;
			public static final int SENDQUEUE = 4;
			public static final int RECEIVING = 5;
			public static final int MESSAGES = 6;
			public static final int IDLETIME = 7;
			public static final int LIFETIME = 8;
			public static final int OUTBOUND = 9;
			public static final int SENDING = 10;
			public static final int ROUTING_ADDRESS = 11;
			public static final int DATASENT = 12;
			public static final int RECEIVEQUEUE = 13;
			public static final int DATARECEIVED = 14;
			public static final int COMBINEDQUEUE = 15;
			public static final int COMBINED_DATA_TRANSFERED = 16;
			public static final int PEER_NODE_VERSION = 17;
			public static final int PEER_ARK_REVISION = 18;
			public static final int LOCAL_PORT = 19;
			
			
			
			private int iCompareMode =UNORDERED;
			private ConnectionHandlerComparator secondary;
			public ConnectionHandlerComparator(int iCompareMode)
			{
				this(iCompareMode,null);
			}
			
			public ConnectionHandlerComparator(int iCompareMode,ConnectionHandlerComparator secondarySorting)
			{
				this.iCompareMode = iCompareMode;
				this.secondary = secondarySorting;
			}
    	
			public int compare(Object o1,Object o2)
			{
				return compare ((BaseConnectionHandler)o1,(BaseConnectionHandler)o2);
			}
			
			private int secondaryCompare(int iSign,int primaryResult,BaseConnectionHandler ch1,BaseConnectionHandler ch2)
			{
				if(primaryResult == 0 && secondary != null)
					return secondary.compare(ch1,ch2);
				else
					return iSign*primaryResult; 	
			}
			
			//public int compare(ConnectionHandler ch1,ConnectionHandler ch2)
			//{
			//	return compare(iCompareMode<0?-1:1,ch1,ch2);
			//}

    	
			public int compare(BaseConnectionHandler ch1,BaseConnectionHandler ch2)
			{
				int iSign = iCompareMode<0?-1:1;
				switch(Math.abs(iCompareMode))
				{
					case UNORDERED: //No sorting
						return 0;
					case PEER_ADDRESS:
						return secondaryCompare(iSign,ch1.peerAddress().toString().compareTo(ch2.peerAddress().toString()),ch1,ch2); //TODO: replace with proper IP address comparision
					case PEER_IDENTITY:
						return secondaryCompare(iSign,ch1.peerIdentity().toString().compareTo(ch2.peerIdentity().toString()),ch1,ch2);
					case SENDING_COUNT:
						return secondaryCompare(iSign,new Integer(ch1.blockedSendingTrailer() ? 1 : 0).compareTo(new Integer(ch2.blockedSendingTrailer() ? 1 : 0)),ch1,ch2);
					case SENDQUEUE:
						return secondaryCompare(iSign,new Long(ch1.getTransferAccounter().sendQueueSize()).compareTo(new Long(ch2.getTransferAccounter().sendQueueSize())),ch1,ch2);	
					case RECEIVING:
						return secondaryCompare(iSign,new Boolean(ch1.receiving()).toString().compareTo(new Boolean(ch2.receiving()).toString()),ch1,ch2);
					case MESSAGES:
						return secondaryCompare(iSign,new Long(ch1.messagesReceived()).compareTo(new Long(ch2.messagesReceived())),ch1,ch2);
					case IDLETIME:
						return secondaryCompare(iSign,new Long(ch1.idleTime()).compareTo(new Long(ch2.idleTime())),ch1,ch2);
					case LIFETIME:
						return secondaryCompare(iSign,new Long(ch1.runTime()).compareTo(new Long(ch2.runTime())),ch1,ch2);
					case OUTBOUND:
						return secondaryCompare(iSign,new Boolean(ch1.isOutbound()).toString().compareTo(new Boolean(ch2.isOutbound()).toString()),ch1,ch2);
					case SENDING:
						return secondaryCompare(iSign,new Boolean(ch1.blockedSendingTrailer()).toString().compareTo(new Boolean(ch2.blockedSendingTrailer()).toString()),ch1,ch2);
					case ROUTING_ADDRESS:
						{
							NodeReference n1 = freenet.node.Main.node.rt.getNodeReference(ch1.peerIdentity());
							NodeReference n2 = freenet.node.Main.node.rt.getNodeReference(ch2.peerIdentity());
							String s1=(n1==null?"":n1.firstPhysicalToString());
							String s2=(n2==null?"":n2.firstPhysicalToString());
							return secondaryCompare(iSign,s2.compareTo(s1),ch1,ch2); //Reverse order on s1 and s2 is intended. Better to place non-routable CH:s at the end
						}
					case DATASENT:
						return secondaryCompare(iSign,new Long(ch1.getTransferAccounter().totalDataSent()).compareTo(new Long(ch2.getTransferAccounter().totalDataSent())),ch1,ch2);
					case RECEIVEQUEUE:
						return secondaryCompare(iSign,new Long(ch1.getTransferAccounter().receiveQueueSize()).compareTo(new Long(ch2.getTransferAccounter().receiveQueueSize())),ch1,ch2);
					case DATARECEIVED:
							return secondaryCompare(iSign,new Long(ch1.getTransferAccounter().totalDataReceived()).compareTo(new Long(ch2.getTransferAccounter().totalDataReceived())),ch1,ch2);
					case COMBINEDQUEUE:
							return secondaryCompare(iSign,new Long(ch1.getTransferAccounter().receiveQueueSize()+ch1.getTransferAccounter().sendQueueSize()).compareTo(new Long(ch2.getTransferAccounter().receiveQueueSize()+ch2.getTransferAccounter().sendQueueSize())),ch1,ch2);
					case COMBINED_DATA_TRANSFERED:
							return secondaryCompare(iSign,new Long(ch1.getTransferAccounter().totalDataSent()+ch1.getTransferAccounter().totalDataReceived()).compareTo(new Long(ch2.getTransferAccounter().totalDataSent()+ch2.getTransferAccounter().totalDataReceived())),ch1,ch2);
					case PEER_NODE_VERSION:
					{
						NodeReference n1 = ch1.targetReference();
						NodeReference n2 = ch2.targetReference();
						String s1=(n1==null?"":n1.getVersion());
						String s2=(n2==null?"":n2.getVersion());
						return secondaryCompare(iSign,s1.compareTo(s2),ch1,ch2);
					}
					case PEER_ARK_REVISION:
					{
						NodeReference n1 = ch1.targetReference();
						NodeReference n2 = ch2.targetReference();
						Long l1=(n1==null?new Long(-1):new Long(n1.revision()));
						Long l2=(n2==null?new Long(-1):new Long(n2.revision()));
						return secondaryCompare(iSign,l1.compareTo(l2),ch1,ch2);
					}
					case LOCAL_PORT:
						return secondaryCompare(iSign,new Long(ch1.getLocalPort()).compareTo(new Long(ch2.getLocalPort())),ch1,ch2);
					default:
						return 0;	
				}
			}
		}
