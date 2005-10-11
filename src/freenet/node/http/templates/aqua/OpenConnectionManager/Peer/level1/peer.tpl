<table border="0" cellspacing="0" cellpadding="0" class="box">
  <tr> 
    <td width="15" height="8" background="##DISTKEY##/servlet/images/aqua/t-infg.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="8" alt=""></td>
    <td height="8" background="##DISTKEY##/servlet/images/aqua/t-inf.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="8" alt=""></td>
    <td width="15" height="8" background="##DISTKEY##/servlet/images/aqua/t-infd.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="8" alt=""></td>
  </tr>

  <tr>
   <td width="15" background="##DISTKEY##/servlet/images/aqua/t-g.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="15" alt=""></td>
   <td bgcolor="#e7edfe" align="center" width="100%" class="title">##NODEADDRESS##,&nbsp;Ver:&nbsp;##NODEVERSION##</td>
   <td width="15" background="##DISTKEY##/servlet/images/aqua/t-d.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="15" alt=""></td>
  </tr>

  <tr>
    <td height="1" width="15" background="##DISTKEY##/servlet/images/aqua/b-g.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="1" alt=""></td>
    <td height="1" bgcolor="#777777"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="1" alt=""></td>
    <td height="1" width="15" background="##DISTKEY##/servlet/images/aqua/b-d.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="1" alt=""></td>
  </tr>

  <tr>
    <td width="15" height="8" background="##DISTKEY##/servlet/images/aqua/g.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="8" alt=""></td>
    <td height="8" bgcolor="#f3f3f3"></td>
    <td width="15" height="8" background="##DISTKEY##/servlet/images/aqua/d.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="8" alt=""></td>
  </tr>
  
  <tr>
   <td width="15" background="##DISTKEY##/servlet/images/aqua/g.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="15" alt=""></td>
   <td bgcolor="#f3f3f3" align="left" width="100%" class="content">
    <table class="ocmPeerData">
      <tr>
        <th>Data&nbsp;queued&nbsp;(<span class="outbound">out</span>/<span class="inbound">in</span>)</th>
        <td><span class="outbound">##DATAQUEUEDOUT##</span>/<span class="inbound">##DATAQUEUEDIN##</span></td>
        <td rowspan="22" class="ocmPeerMessages">##MESSAGESLIST##</td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##DATAQUEUEDOUTBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=7810D0"></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##DATAQUEUEDINBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=252597"></td>
      </tr>
      
      <tr>
        <th>Data&nbsp;transferred&nbsp;(<span class="outbound">sent</span>/<span class="inbound">received</span>)</th>
        <td><span class="outbound">##DATATRANSFERREDOUT##</span>/<span class="inbound">##DATATRANSFERREDIN##</span></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##DATATRANSFERREDOUTBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=7810D0"></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##DATATRANSFERREDINBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=252597"></td>
      </tr>
      
      <tr>
        <th>Messages&nbsp;queued</th>
        <td class="inbound">##MESSAGESQUEUED##</td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##MESSAGESQUEUEDBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=252597"></td>
      </tr>
      
      <tr>
        <th>Messages&nbsp;transferred&nbsp;(<span class="outbound">sent</span>/<span class="inbound">received</span>)</th>
        <td><span class="outbound">##MESSAGESTRANSFERREDOUT##</span>/<span class="inbound"##MESSAGESTRANSFERREDIN##</span></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##MESSAGESTRANSFERREDOUTBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=7810D0"></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##MESSAGESTRANSFERREDINBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=252597"></td>
      </tr>

      <tr>
        <th>Trailers&nbsp;in&nbsp;transit&nbsp;(<span class="outbound">sending</span>/<span class="inbound">receiving</span>)</th>
        <td><span class="outbound">##TRAILERSINTRANSITOUT##</span>/<span class="inbound">##TRAILERSINTRANSITIN##</span></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##TRAILERSINTRANSITOUTBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=7810D0"></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##TRAILERSINTRANSITINBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=252597"></td>
      </tr>

      <tr>
        <th>Time&nbsp;(<span class="outbound">idle</span>/<span class="inbound">life</span>)</th>
        <td><span class="outbound">##TIMEIDLE##</span>/<span class="inbound">##TIMELIFE##</span></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##TIMEIDLEBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=7810D0"></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##TIMELIFEBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=252597"></td>
      </tr>

      <tr>
        <th>Requests&nbsp;(<span class="outbound">sent</span>/<span class="inbound">received</span>)</th>
        <td><span class="outbound">##REQUESTSOUT##</span>/<span class="inbound">##REQUESTSIN##</span></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##REQUESTSOUTBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=7810D0"></td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##REQUESTSINBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=7810D0"></td>
      </tr>

      <tr>
        <th>Outbound&nbsp;connection&nbsp;success&nbsp;ratio</th>
        <td>##CONNECTIONSUCCESS##</td>
      </tr>
      <tr style="height:3px">
        <td colspan="2"><img height="4" width="##CONNECTIONSUCCESSBARLENGTH##" src="##DISTKEY##/servlet/coloredpixel?color=252597"></td>
      </tr>

    </table>
   </td>
   <td width="15" background="##DISTKEY##/servlet/images/aqua/d.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="15" alt=""></td>
  </tr>

  <tr> 
    <td width="15" height="15" background="##DISTKEY##/servlet/images/aqua/coinsupg.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="15" alt=""></td>
    <td height="15" background="##DISTKEY##/servlet/images/aqua/sup.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="15" alt=""></td>
    <td width="15" height="15" background="##DISTKEY##/servlet/images/aqua/coinsupd.png"><img src="##DISTKEY##/servlet/images/aqua/space15_15.png" width="15" height="15" alt=""></td>
  </tr>
</table>