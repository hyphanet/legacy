<form method="post" enctype="multipart/form-data" action="##DISTKEY##/servlet/Insert">
                    
                            <table cellpadding="0" width="100%" border="0">
                                <tr>
                                    <th>Key:</th>
                                    <td><input size="50" name="key" value="CHK@"></td>
                                </tr>
                                <tr>
                                    <th>Hops-to-live:</th>
                                    <td><input size="2" name="htl" value="25"></td>
                                </tr>
                                <tr>
                                    <th>Threads:</th>
                                    <td><input size="2" name="threads" value="30"></td>
                                </tr>
                                <tr>
                                    <th>Retries:</th>
                                    <td><input size="2" name="retries" value="3"></td>
                                </tr>
                                <tr>
                                    <th>File:</th>
                                    <td><input type="file" name="filename" size="50"></td>
                                </tr>
                                <tr>
                                    <th>Mime Type:</th>
                                    <td>
                                        <select name="content-type">
                                            <option selected value="auto">Use file extension</option>
                                            <option value="text/plain">Plain text</option>
                                            <option value="text/html">HTML text</option>
                                            <option value="image/gif">GIF image</option>
                                            <option value="image/jpeg">JPEG image</option>
                                            <option value="audio/wav">WAV sound</option>
                                            <option value="audio/mpeg">MP3 music</option>
	                                    <option value="audio/x-vorbis">OGG Vorbis music</option>
                                            <option value="video/mpeg">MPEG audio/video</option>
	                                    <option value="video/x-msvideo">AVI audio/video</option>
                                            <option value="video/x-ms-asf">ASF audio/video</option>
	                                    <option value="application/x-ogg">OGM audio/video</option>
                                            <option value="application/pdf">PDF file</option>
                                            <option value="application/postscript">Postscript document</option>
                                            <option value="application/octet-stream">Other</option>
                                        </select>
                                    </td>
                                </tr>
                                <tr>
                                    <th colspan="2"><input type="submit" value="Insert"></th>
                                </tr>
                            </table>

</form>
