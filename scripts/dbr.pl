#!/usr/bin/env perl

use Getopt::Long;

GetOptions( 'off:i' => \$offset,
            'inc:i' => \$increment );

@ARGV or usage() && exit;

$increment = 86400 unless $increment > 0;
$offset = 0 unless $offset > 0;
die "offset must be less than increment" unless $offset < $increment;

$now = time;
$now = sprintf('%x', $now - (($now - $offset) % $increment));

$uri = shift @ARGV;
$uri =~ s{^(.*[/@]|)}{\1$now-};

print "$uri\n";


sub usage {
    print <<USAGE;
Usage: $0 [--off offset] [--inc increment] <URI>
USAGE
}



