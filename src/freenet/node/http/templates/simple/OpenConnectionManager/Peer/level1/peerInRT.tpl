<div class="box">
  <div class="title">##NODEADDRESS##,&nbsp;Ver:&nbsp;##NODEVERSION##</div>
  <!-- level1 -->
  <div class="content">
    <table class="ocmPeerData">
      <tr>
        <th>Data&nbsp;queued&nbsp;(<span class="outbound">out</span>/<span class="inbound">in</span>)</th>
        <td><span class="outbound">##DATAQUEUEDOUT##</span>/<span class="inbound">##DATAQUEUEDIN##</span></td>
        <th>Estimated&nbsp;routing&nbsp;time&nbsp;(min/max)</th>
        <td>##ESTIMATEDROUTINGTIMEMIN##/##ESTIMATEDROUTINGTIMEMAX##</td>
        <td rowspan="22" class="ocmPeerMessages">##MESSAGESLIST##</td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar outboundbackground" style="width:##DATAQUEUEDOUTBARLENGTH##px" /></td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar inboundbackground" style="width:##DATAQUEUEDINBARLENGTH##px" /></td>
      </tr>
      
      <tr>
        <th>Data&nbsp;transferred&nbsp;(<span class="outbound">sent</span>/<span class="inbound">received</span>)</th>
        <td><span class="outbound">##DATATRANSFERREDOUT##</span>/<span class="inbound">##DATATRANSFERREDIN##</span></td>
        <th>Probability&nbsp;of&nbsp;DNF&nbsp;(min/max)</th>
        <td>##PDNFMIN##/##PDNFMAX##</td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar outboundbackground" style="width:##DATATRANSFERREDOUTBARLENGTH##px" /></td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar inboundbackground" style="width:##DATATRANSFERREDINBARLENGTH##px" /></td>
      </tr>
      
      <tr>
        <th>Messages&nbsp;queued</th>
        <td>##MESSAGESQUEUED##</td>
        <th>Time&nbsp;for&nbsp;DNF&nbsp;(min/max)</th>
        <td>##TDNFMIN##/##TDNFMAX##</td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar" style="width:##DATATRANSFERREDBARLENGTH##px" /></td>
      </tr>
      
      <tr>
        <th>Messages&nbsp;transferred&nbsp;(<span class="outbound">sent</span>/<span class="inbound">received</span>)</th>
        <td><span class="outbound">##MESSAGESTRANSFERREDOUT##</span>/<span class="inbound">##MESSAGESTRANSFERREDIN##</span></td>
        <th>Time&nbsp;for&nbsp;successful&nbsp;search&nbsp;(min/max)</th>
        <td>##TSUCCESSFULSEARCHMIN## / ##TSUCCESSFULSEARCHMAX##</td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar outboundbackground" style="width:##MESSAGESTRANSFERREDOUTBARLENGTH##px" /></td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar inboundbackground" style="width:##MESSAGESTRANSFERREDINBARLENGTH##px" /></td>
      </tr>

      <tr>
        <th>Trailers&nbsp;in&nbsp;transit&nbsp;(<span class="outbound">sending</span>/<span class="inbound">receiving</span>)</th>
        <td><span class="outbound">##TRAILERSINTRANSITOUT##</span>/<span class="inbound">##TRAILERSINTRANSITIN##</span></td>
        <th>Transfer&nbsp;rate&nbsp;(min/max)</th>
        <td>##TRANSFERRATEMIN## / ##TRANSFERRATEMAX##</td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar outboundbackground" style="width:##TRAILERSINTRANSITOUTBARLENGTH##px" /></td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar inboundbackground" style="width:##TRAILERSINTRANSITINBARLENGTH##px" /></td>
      </tr>

      <tr>
        <th>Time&nbsp;(<span class="outbound">idle</span>/<span class="inbound">life</span>)</th>
        <td><span class="outbound">##TIMEIDLE##</span>/<span class="inbound">##TIMELIFE##</span></td>
        <td rowspan="8" colspan="2" class="ocmPeerRT"><img height="140" width="400" alt="Estimators for ##ADDRESS##" src="/servlet/nodeinfo/networking/ocm/combined?identity=##IDENTITY##" /></td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar outboundbackground" style="width:##TIMEIDLEBARLENGTH##px" /></td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar inboundbackground" style="width:##TIMELIFEBARLENGTH##px" /></td>
      </tr>

      <tr>
        <th>Requests&nbsp;(<span class="outbound">sent</span>/<span class="inbound">received</span>)</th>
        <td><span class="outbound">##REQUESTSOUT##</span>/<span class="inbound">##REQUESTSIN##</span></td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar outboundbackground" style="width:##REQUESTSOUTBARLENGTH##px" /></td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar inboundbackground" style="width:##REQUESTSINBARLENGTH##px" /></td>
      </tr>

      <tr>
        <th>Outbound&nbsp;connection&nbsp;success&nbsp;ratio</th>
        <td>##CONNECTIONSUCCESS##</td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar" style="width:##CONNECTIONSUCCESSBARLENGTH##px" /></td>
      </tr>

    </table>
  </div>
</div>
