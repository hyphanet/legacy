#!/usr/bin/perl -w 
#
# show routes of messages in freenets watchme network
#

use strict;
use GraphViz;
use Getopt::Std;
use File::Basename;

my $debug = 1; # default debug level
my $par = 'test';
my %data;
my $line;
my %opts;

getopts( 'p1ocm:d:', \%opts );

unless( %opts ){ 
  
  print "\nusage: show_routing.pl [options] logfiles
  
   options:
  
   -p                try to generate postscript graph(s) as messageID.ps
   -1                generate one postscript page - otherwise tile (both A4)
   -o                try to generate dot file as messageID.ps
   -c                give a count of the occurence of each message, as well as
                     originating node and starting time
   -m messageID      only work on message with ID messageID
   -d debuglevel     print various useless debugging output
  
   -c cannot be used at the same time as -p or -o
   -p and -o must be used in conjunction with -m
   
   You need to have GraphViz and the Perl GraphViz Module installed!
   
   made by Chris Vogel <chrinet\@ween.de>\n\n";

  exit 1;

}

$opts{ 'd' } && ( $debug = $opts{ 'd' } );

# read in what we have - uh, uhhh...
LINE: while( $line = <> ){
  
  chomp $line;
  &debug( 9, "working on $line" );
  
  # try to match only but every interesting line and break it up
  if( $line =~ 
    /^(\d+)\s+(\w+)\s+(\w+)\s+([\w\d]+)\s+(\w+)\/([\d.]+):(\d+)\s*$/ 
    # $1 = time
    # $2 = received|sent|??
    # $3 = message type
    # $4 = messageID
    # $5 = protocol
    # $6 = node-address
    # $7 = port
  ){
    
    debug( 8, "found: $line" );
    
    # what are Accepted messages for?
    next LINE if( ( $opts{ 'm' } && $4 ne $opts{ 'm' } ) ||
      $3 eq 'Accepted' );

    if( $opts{ 'c' } ){

      $data{ $4 }[ 0 ] += 1;

      if( $data{ $4 }[ 0 ] == 1 ){
        
        @{ $data{ $4 } }[ 1, 2 ] = ( $1, $6 );
	&debug( 7, "added time $data{ $4 }[ 1 ] and node $data{ $4 }[ 2 ] for $4" );
	
      } elsif( $data{ $4 }[ 1 ] && ( $1 < $data{ $4 }[ 1 ] ) ){
        
	@{ $data{ $4 } }[ 1, 2 ] = ( $1, $6 );
	&debug( 7, "new time $data{ $4 }[ 1 ] and node $data{ $4 }[ 2 ] for $4" );

      }

    } else {
    
      $data{ $4 }{ $1 } = [ basename( $ARGV ), $2, $3, $5, $6, $7 ];

    }

  } else {
  
    debug( 1, "ignored line $. from $ARGV: $line" );
    
  }

}

# do some output
if( $opts{ 'c' } ){

  foreach my $messageID ( sort( keys( %data ) ) ){
  
    printf( "%5i %16s %12s %s\n", @{ $data{ $messageID } }[ 0, 1, 2 ] , $messageID );

  }

} elsif( $opts{ 'm' } ){

  my %node;
  my $count = 0;
  my $graph = GraphViz -> new( 
     directed => 1, no_overlap => 1, rankdir => 0, concentrate => 0,
     # random_start => 0, epsilon => 0.05, # undirected graphs only 
     edge => { fontsize => 10, labelfontsize => 10 }, 
     node => { fontsize => 10, shape => 'box' }, 
     ( $opts{ '1' } ?  ( width => 7.3, height => 11 ) : 
       ( pagewidth => 8, pageheight => 11.5 ) ) 
  );

  foreach my $time ( sort( keys( %{ $data{ $opts{ 'm' } } } ) ) ){
    
    &debug( 6, "$time: @{ $data{ $opts{ 'm' } }{ $time } }" );

    if( $opts{ 'p' } ){

      $count ++;
      my $grey = (1/3)**($count+0.63093)+0.5;
      
      # did we add the nodes to the graph already?
      unless( $node{ ${ $data{ $opts{ 'm' } }{ $time } }[ 0 ] } ){
        
	$graph -> add_node( ${ $data{ $opts{ 'm' } }{ $time } }[ 0 ],
	  style => 'filled', fillcolor => ( '0, 0, ' . $grey ),
	);
	$node{ ${ $data{ $opts{ 'm' } }{ $time } }[ 0 ] } = 1;
	&debug( 7, "add_node ${ $data{ $opts{ 'm' } }{ $time } }[ 0 ]" );

      }
      unless( $node{ ${ $data{ $opts{ 'm' } }{ $time } }[ 4 ] } ){
        
	$graph -> add_node( ${ $data{ $opts{ 'm' } }{ $time } }[ 4 ], 
	  style => 'filled', fillcolor => ( '0, 0, ' . $grey ),
	);
	$node{ ${ $data{ $opts{ 'm' } }{ $time } }[ 4 ] } = 1;
	&debug( 7, "add_node ${ $data{ $opts{ 'm' } }{ $time } }[ 4 ]" );

      }

      # make an edge from the message
      my %edge;
      if( ${ $data{ $opts{ 'm' } }{ $time } }[ 1 ] eq 'received' ){
        
	%edge = ( ${ $data{ $opts{ 'm' } }{ $time } }[ 4 ] =>
	  ${ $data{ $opts{ 'm' } }{ $time } }[ 0 ] );

      } else {
        
	%edge = ( ${ $data{ $opts{ 'm' } }{ $time } }[ 0 ] =>
	  ${ $data{ $opts{ 'm' } }{ $time } }[ 4 ] );

      }
      
      $graph -> add_edge( 
        %edge, 
	label => "$count:\n" .
        ${ $data{ $opts{ 'm' } }{ $time } }[ 2 ], 
	headlabel => ${ $data{ $opts{ 'm' } }{ $time } }[ 5 ] );

      &debug( 7, "add_edge ${ $data{ $opts{ 'm' } }{ $time } }[ 0 ] => " .
        ${ $data{ $opts{ 'm' } }{ $time } }[ 4 ] . ', label => ' .
	"${ $data{ $opts{ 'm' } }{ $time } }[ 1 ] " .
	${ $data{ $opts{ 'm' } }{ $time } }[ 2 ] );
      
    } else {

      print "$time: @{ $data{ $opts{ 'm' } }{ $time } }\n";

    }

  }

  print $graph -> as_ps( "$opts{ 'm' }.ps" ) if( $opts{ 'p' } );
  print $graph -> as_ps( "$opts{ 'm' }.dot" ) if( $opts{ 'o' } );

}

exit 0;

sub debug {
  
  # print diagnostic output
  if( $debug >= $_[0] ){
    
    print STDERR "$_[1]\n";

  }

}

# qed
