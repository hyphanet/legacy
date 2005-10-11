#!/usr/bin/perl
#by Eric Norige
#GPL license  see http://www.gnu.org for a copy
#modify freely

use strict;
use File::Spec::Functions;

my $maxnormal = shift || 15;
my $minnormal = 3;
my ($sec,$min,$hour,$mday,$mon,$year,$wday,$yday,$isdst) = localtime(time);
my $logdir = ".";
my $archivedir = "../dataarchive/$year.$yday/";
my $voidfile = $archivedir."void";

open VOIDF, ">>$voidfile";
&gettoarchive($logdir);
close VOIDF;

my @ids = &all_ids();

print "Number of unique Message IDs: ", scalar @ids, "\n";
    
my ($count_types, $counts, $nonreporting, $sshtl) = &analyze(@ids);

die "no data processed" if scalar keys %$counts == 0;

my $count_ids = &sum (map { $counts->{$_} } (keys %$counts));
my $count_size = &sum (map { $counts->{$_} * $_ } (keys %$counts));

my $average = $count_size / $count_ids;
print "ids: $count_ids mesgs: $count_size avg: $average\n";

my $count_big = &sum (map {$counts->{$_} > $minnormal ? $counts->{$_} : 0} (keys %$counts));
my $count_size_big = &sum (map {$counts->{$_} > $minnormal ? $counts->{$_} * $_ : 0} (keys %$counts));

if ($count_big > 0) {
    my $big_avg = $count_size_big / $count_big;
    print "For requests larger than $minnormal hops:\n";
    print "ids: $count_big mesgs: $count_size_big avg: $big_avg\n";
} else {
    print "no chains had length greater than $minnormal\n";
}

foreach (sort {$a <=> $b} keys %$counts) {
    print "$_\t$counts->{$_}\n";
}

print "Message type counts:\n";
foreach (sort keys %$count_types) {
    my $elem = $count_types->{$_};
    my $sent = $elem->{sent} + 0;
    my $recd = $elem->{received} + 0;
    my $verified = $elem->{confirmed} + 0;
    print "$_ -> $sent out, $recd in, $verified paired\n";
}

print "Nonreporting nodes, number of messages: ";
print "($_,$nonreporting->{$_})\t" foreach (sort keys %$nonreporting);
print "\n";

exit 0;


##################################################################
#                      End of Main                               #
##################################################################

sub sum (@) {
    my $accum = shift;
    while (defined (my $val = shift () ) ) {
	$accum += $val;
    }
    return $accum;
}

sub analyze {
    my %count_types = ();
    my %counts = ();
    my %nonreporting = ();
    my %htls = ();

    my $sumsizehtl = 0;
    foreach my $id (@_) {
	my $path = &readid($id);
	my %node_ids = ();
	my $maxhtl = -1;
	foreach (@$path) {

	    $count_types{$_->{"mesg"}}{$_->{"sr"}}++;
	    if ($_->{sr} ne "confirmed") {
		my $other = $_->{sr} eq "sent" ? $_->{to_node} : $_->{from_node};
		$nonreporting{$other}++;
	    }
	    $node_ids{$_->{from_node}} = 1;
	    $node_ids{$_->{to_node}} = 1;
	    $maxhtl = $_->{htl} if $_->{htl} > $maxhtl;
	}
	my $size = scalar keys %node_ids;
	$counts{$size}++;
	$htls{$maxhtl}++;
	$sumsizehtl += $size / $htl;
	
	next if $path->[0]{mesg} eq "Void";
	if ($size > $maxnormal) {
	    print "Large: $id size: $size\n";
	}
    }

    return (\%count_types, \%counts, \%nonreporting, $sumsizehtl);
}

##################################################################
#                      Routines for data on disk                 #
##################################################################


sub gettoarchive ($$) {
    my $dir = shift;
    my $archivedir = shift;
    
    my @lines = ();

    mkdir $archivedir unless -d $archivedir;

    #get a list of files in the $dir to suck data from
    my @datafiles = ();
    if (opendir (DIR, $dir)) {
	@datafiles = grep {-f (catdir($dir,$_)) 
			       and /^\d+\.\d+\.\d+\.\d+$/} readdir(DIR); 
	close DIR;
    }
    
    print STDERR "Reading ";
    #suck data from the files
    foreach my $file (@datafiles) {
	my $logfile = catdir($dir, $file);
	print STDERR "$file ";
	open INFILE, $logfile;
	rename $logfile, "$logfile.proc";
	my $path = [];
	my ($time, $id, $sr, $mesg, $other_node, $htl);
      line:
	while (<INFILE>) {
	    ($time, $sr, $mesg, $id, $other_node, $htl) = split /\s/;
	    my ($other) = $other_node =~ /tcp\/(.*):\d+/;  #scrub other
	    my ($from,$to) = $sr eq "sent" 
		? ($file, $other) 
		: ($other,$file);
	    $path = &readid($id);
	    foreach my $seen (@$path) {
		next unless $seen->{mesg} eq $mesg;
		next unless $seen->{to_node} eq $to;
		next unless $seen->{from_node} eq $from;
		next unless $seen->{htl} eq $htl;
		next line if $sr eq $seen->{sr};
		$seen->{sr} = "confirmed";
		next line;
	    }
	    my %struct = ( time => $time,
			   sr => $sr,
			   mesg => $mesg,
			   from_node => $from,
			   to_node => $to,
			   htl => $htl + 0
			   );
	    push @$path, \%struct;
	} continue 
	{ &writeid($id, $path);	}	
	close INFILE;
	system "cat $logfile.proc >> $logfile.done";
	unlink "$logfile.proc";
    }
    print STDERR "\n";
}

sub readid ($) {
    my $id = shift;
    my $path = [];

    my $file = &file_of_id($id);
    if (open INID, $file) {
	while (<INID>) {
	    next unless $_;
	    my ($time, $sr, $mesg, $from, $to, $htl) = split /:/;
	    chomp $htl;
	    my %struct = ( time => $time,
			   sr => $sr,
			   mesg => $mesg,
			   from_node => $from,
			   to_node => $to,
			   htl => $htl + 0
			   );
	    push @$path, \%struct;
	}
    }
    return $path;
}

sub writeid ($$) {
    my $id = shift;
    my $path = shift;

    if (scalar @$path == 1 && $path->[0]{mesg} eq "Void") {
	my $vmesg = $path->[0];
	print VOIDF
	    $vmesg->{time}, ":",
	    $vmesg->{sr}, ":",
	    $vmesg->{from_node}, ":",
	    $vmesg->{to_node}, "\n";
    } else {
	my $file = &file_of_id($id);
	open OUTID, ">$file" || die "cannot create/replace $file: $!";
	foreach my $mesg (sort {$a->{time} <=> $b->{time}} @$path) {
	    next if $mesg->{from_node} eq "";
	    next if $mesg->{to_node} eq "";
	    print OUTID
		$mesg->{time}, ":",
		$mesg->{sr}, ":",
		$mesg->{mesg}, ":",
		$mesg->{from_node}, ":",
		$mesg->{to_node}, ":",
		$mesg->{htl}, "\n";
	}
	close OUTID;
    }
}

sub file_of_id ($) {
    my $id = shift;
    my ($pre, $post) = $id =~ /(.{2})(.{14})/;
    my $dir = $archivedir. "/" .$pre;

    mkdir $dir unless -d $dir;
    return catdir $dir, $post;
}

sub all_ids () {
    my @ids = ();

    if (opendir HEAD, $archivedir) {
	my $pre;
	while ($pre = readdir HEAD) {
	    if (opendir SUB, catdir $archivedir, $pre) {
		my @new = map {$pre . $_} readdir SUB;
		push @ids, @new;
		close SUB;
	    }
	}
	close HEAD;
    }
    
    return @ids;
}
