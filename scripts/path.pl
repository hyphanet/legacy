#!/usr/bin/perl -w
# Written by M. David Allen <mda@idatar.com>
# Modified by Eric Norige <thelema314@bigfoot.com>
# Released under the terms of the GNU General Public License
# This program outputs a file which is fed into "Dot" to create a PS graph.
#
# Use like this:
# ./path.pl 9e5c2ac56722f0bb path/to/logs
# dot -Tps path-9e5c2ac56722f0bb.dot -o graph.ps
# Enjoy graph.ps! 
########################################################################
use strict;

$|++;  #non-buffered IO

sub usage ($) {
    my $msg = shift;
    print $msg, "\n";
    print "Usage: path.pl request [dir] [filename]\n";
    exit 0;
}

my $request = shift;  #from ARGV
&usage("Request ID needed") if !$request;
my $dir = shift || ".";  #the directory to search for datafiles
my $filename = shift || "path-$request.dot";  #from ARGV


#get a list of files in the $dir to suck data from
opendir (DIR, $dir) or die "cannot opendir $dir";
my @datafiles = grep {-f "$dir/$_" and /^\d+\.\d+\.\d+\.\d+$/} readdir(DIR); 
close DIR;

&usage ("no datafiles in directory $dir; please specify a directory") unless @datafiles;

my @path = ();

print "Reading: ";
#suck data from the files
foreach my $file (@datafiles) {
    print "$file ";
    open INFILE, "$dir/$file" || die "cannot open $dir/$file: $!";
    my ($time, $sr, $mesg, $id, $other_node, $htl, $other, $from, $to);
  line:
    while (<INFILE>) {
	($time, $sr, $mesg, $id, $other_node, $htl) = split /\s/;
	next unless ($id eq $request); #skip lines that don't have the right ID.
	($other) = $other_node =~ /tcp\/(.*):\d+/;  #scrub other
	($from,$to) = $sr eq "sent" ? ($file, $other) : ($other,$file);
	$from =~ s/(\d+)\.(\d+)\.(\d+)\.(\d+)/Node\1_\2_\3_\4/;  
	$to   =~ s/(\d+)\.(\d+)\.(\d+)\.(\d+)/Node\1_\2_\3_\4/;
	
	foreach my $seen (@path) {
	    next unless $seen->{mesg} eq $mesg;
	    next unless $seen->{to_node} eq $to;
	    next unless $seen->{from_node} eq $from;
#	    next unless $seen->{htl} eq $htl;  #reinstate when all nodes are logging HTL
	    next line if $sr eq $seen->{sr};
	    $seen->{sr} = "confirmed";
	    next line;
	}
	my %struct = ( time => $time,
		       sr => $sr,
		       mesg => $mesg,
		       from_node => $from,
		       to_node => $to,
		       htl => $htl
		       );
	push @path, \%struct;
	
    }
    close INFILE;
}
print "\n";
@path = sort {$a->{time} <=> $b->{time}} @path;

# We're now done parsing data, and we can start to build the DOT file which
# will be turned into the graph....

# Define edge labels by request type
# This must be an exhaustive list.  If it isn't, then the script will report
# warnings on undefined types.  Please add anything I'm missing.
my %typeCodes = ("Accepted"             => "Acc",
                 "AnnouncementComplete" => "AC",
                 "AnnouncementExecute"  => "AE",
                 "AnnouncementReply"    => "AR",
                 "AnnouncementFailed"   => "AF",
		 "ClientDelete"         => "CDelete",
		 "ClientGet"            => "CG",
		 "ClientHello"          => "CH",
		 "ClientPut"            => "CP",
		 "DataChunk"            => "DCh",
		 "DataFound"            => "DF",
                 "DataNotFound"         => "DNF",
                 "DataReply"            => "DReply",
                 "DataRequest"          => "DReq",
		 "Failed"               => "Fail",
		 "FormatError"          => "Format",
		 "GenerateCHK"          => "GenCHK",
		 "GenerateSVKPair"      => "GenSVK",
		 "InsertReply"          => "InsRep",
		 "InsertRequest"        => "InsReq",
		 "KeyCollision"         => "KC",
                 "NodeAnnouncement"     => "NA",
                 "NodeHello"            => "NH",
		 "Pending"              => "PND",		 
		 "QueryAborted"         => "QAbort",
                 "QueryRejected"        => "QRej",
                 "QueryRestarted"       => "QR",
		 "Restarted"            => "R",
		 "RouteNotFound"        => "RNF",
                 "StoreData"            => "SD",
		 "Success"              => "SXS",
		 "URIError"             => "URIErr",
		 "Void"                 => "Void");

# Define color by type here.  It does not have to be an exhaustive list. 
# Anything not listed here will be black.
my %colors   = ("QRej"        => "red",
                "DR"          => "black",
                "A"           => "green",
                "DReq"        => "green",
                "NA"          => "pink",
                "SD"          => "yellow",
                "DNF"         => "cyan");

my %nodelist = ();       # List of node IP addresses and their labels in graph.
my %verified = ();       # nodes which have at least one message verified

foreach my $step (@path) {
    my($reporter, $toNode) = ($step->{'from_node'},
			      $step->{'to_node'});
    $nodelist{$reporter} = $reporter;
    $nodelist{$toNode} = $toNode;
    if ($step->{sr} eq "confirmed") {
	$verified{$reporter} = 1;
	$verified{$toNode} = 1;
    }
}

my %ts = ();             # Various types of messages seen (for debugging)
my %type_count = ();     # Counts how many messages of each type

open OUT, ">$filename" or die "cannot open $filename: $!";
print "Writing to $filename\n";
print OUT "digraph G {\n";   # Begin DOT output

foreach my $step (@path) {

    # Change our ugly IP addresses into usable node names    
    my $from = $step->{'from_node'};
    my $to = $step->{'to_node'};

    # The "step" attribute holds whether this was a QRej, DNF, etc.
    my $stype = $step->{mesg};
    $type_count{$stype}++;
    
    # Lookup the type code for the edge label.
    my $label = $typeCodes{$stype} || print "Unknown message type: $stype\n";
    #get a HTL value for appending to request messages
    my $htl = $step->{htl};
    $htl = "" if $htl == -1;
    
    # Attributes for the line connecting the two nodes.
    my $attrs = "label=$label$htl";
    
    my $color = $colors{$label} || "black";
    $attrs .= ",color=$color";

    #make more important edges have higher weight
    my $weight = 1;
    $weight += 1 if $stype eq "Acc";
    $weight += 2 if $stype eq "DNF";
    $weight += 2 if $stype eq "DF";
    $weight += 5 if $stype =~ /^C/;
    $attrs .= ",weight=$weight" if $weight != 1;
    
#    $attrs .= ",style=dashed" unless $step->{sr} eq "confirmed";
    
    print OUT "  $from -> $to [$attrs]\n";    
} # End foreach

# At the endof the DOT file, output all of the name mappings for the various
# nodes in the graph.  We do this by outputting it's "ugly" name, and then
# assigning it a label that is it's node number.
foreach my $key (keys(%nodelist)) {
    if ($verified{$key}) {
	print OUT "  $key [label=$key,style=solid]\n";
    } else {
	print OUT "  $key [label=$key,shape=box,style=dashed]\n";
    }
} # End foreach

print OUT "}\n";  # End DOT output

print "Message type counts:\n";
foreach (sort keys %type_count) {
    print "$_ -> $type_count{$_}\n";
}

exit(0);  # We're done.
