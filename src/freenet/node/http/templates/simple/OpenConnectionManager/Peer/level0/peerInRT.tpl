<div class="box">
  <div class="title">##NODEADDRESS##,&nbsp;Ver:&nbsp;##NODEVERSION##</div>
  <!-- level0 -->
  <div class="content">
    <table class="ocmPeerData">
      <tr>
        <th>Data&nbsp;transferred</th>
        <td>##DATATRANSFERRED##</td>
        <th>Routing&nbsp;Information</th>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar" style="width:##DATATRANSFERREDBARLENGTH##px" /></td>
        <td rowspan="12" class="ocmPeerRT"><img height="140" width="400" alt="Estimators for ##ADDRESS##" src="/servlet/nodeinfo/networking/ocm/combined?identity=##IDENTITY##" /></td>
      </tr>
      
      <tr>
        <th>Messages&nbsp;transferred</th>
        <td>##MESSAGESTRANSFERRED##</td>
      </tr>
      <tr>
        <td colspan="2"><div class="horizontalBar" style="width:##DATATRANSFERREDBARLENGTH##px" /></td>
      </tr>
      
      <tr>
        <th>Trailers&nbsp;in&nbsp;transit&nbsp;(<span class="outbound">sending</span>/<span class="inbound">receiving</span>)</th>
        <td><span class="outbound">##TRAILERSINTRANSITOUT##</span>/<span class="inbound">##TRAILERSINTRANSITIN##</span></td>
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

    </table>
  </div>
</div>