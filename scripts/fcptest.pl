#!/usr/bin/env perl

use strict;

use Getopt::Long;
use Socket;

my ($host, $port, $dlen, $mlen, $htl, $pwd, $stdio);

GetOptions( 'host:s' => \$host,
            'port:i' => \$port,
            'dlen:i' => \$dlen,
            'mlen:i' => \$mlen,
            'htl:i'  => \$htl,
            'pwd:s'  => \$pwd,
            ''       => \$stdio );

my $addr = inet_aton( length($host) ? $host : '127.0.0.1' );
$port = 8481 unless $port;


my $cmd = shift @ARGV;

for ($cmd) {
    /^hello$/i && do { hello(); last };
    /^chk$/i   && do { chk();   last };
    /^svk$/i   && do { svk();   last };
    /^get$/i   && do { get();   last };
    /^put$/i   && do { put();   last };
    /^diag$/i  && do { diag();  last };
    usage();
}

sub hello {
    my $sock = init_conn();
    send_command($sock, qw/ClientHello EndMessage/);
    print_reply($sock);
}

sub chk {
    my $fh   = get_stream(shift @ARGV);
    my $sock = init_conn();
    send_command( $sock,
                  'GenerateCHK',
                  sprintf('DataLength=%x', $dlen),
                  ($mlen ? sprintf('MetadataLength=%x', $mlen) : ()),
                  'Data' );
    send_data($sock, $fh);
    print_reply($sock);
}

sub svk {
    my $sock = init_conn();
    send_command($sock, qw/GenerateSVKPair EndMessage/);
    print_reply($sock);
}

sub get {
    my $uri = shift @ARGV;
    my $sock = init_conn();
    send_command( $sock,
                  'ClientGet',
                  "URI=$uri",
                  sprintf('HopsToLive=%x', $htl),
                  'EndMessage' );
    print_reply($sock, $stdio ? '-' : shift(@ARGV));
}

sub put {
    my $uri  = shift @ARGV;
    my $fh   = get_stream(shift @ARGV);
    my $sock = init_conn();
    send_command( $sock,
                  'ClientPut',
                  "URI=$uri",
                  sprintf('HopsToLive=%x', $htl),
                  sprintf('DataLength=%x', $dlen),
                  ($mlen ? sprintf('MetadataLength=%x', $mlen) : ()),
                  'Data' );
    send_data($sock, $fh);
    print_reply($sock);
}

sub diag {
    my $sock = init_conn();
    send_command( $sock,
                  'GetDiagnostics',
                  ( length($pwd) ? "Password=$pwd" : () ),
                  'EndMessage' );
    print_reply($sock, $stdio ? '-' : shift(@ARGV));
}

sub init_conn {
    local *SOCK;
    my $paddr = sockaddr_in($port, $addr);
    my $proto = getprotobyname('tcp');
    socket(SOCK, PF_INET, SOCK_STREAM, $proto) or die "socket: $!";
    connect(SOCK, $paddr) or die "connect: $!";
    select +((select SOCK), ($| = 1))[0];
    print SOCK pack('c4', 0, 0, 0, 2);
    return *SOCK;
}

sub get_stream {
    local *FH;
    if ($stdio) {
        die "get_stream: unspecified dlen when reading from STDIN\n" unless $dlen;
        open(FH, '-') or die "get_stream: $!";
    }
    else {
        open(FH, shift) or die "get_stream: $!";
        my $size = -s FH;
        $dlen = $size unless $dlen;
        die "get_stream: file is smaller than dlen\n" unless $size >= $dlen;
    }
    return *FH;
}
 
sub send_command {
    my $sock = shift;
    for (@_) {
        print "--> $_\n";
        print $sock "$_\n";
    }
}

sub send_data {
    my ($sock, $fh) = @_;
    my ($buf, $i);
    while ($i = read $fh, $buf, (32768 < $dlen ? 32768 : $dlen)) {
        print "--> <sending $i bytes>\n";
        print $sock $buf;
        $dlen -= $i;
    }
}

sub print_reply {
    my ($sock, $file) = @_;
    my $brief = 1;
    local *FH;
    if (defined $file) {
        open(FH, ">$file") or die "print_reply: $!";
        $brief = 0 if $file eq '-';
    }
    my $len;
    while (<$sock>) {
        print;
        /^Length=([0-9a-fA-F]+)$/ && do { $len = hex($1); next };
        /^Data$/                  && do {
            my $buf;
            die "print_reply: failed to read $len bytes in chunk\n"
                unless $len == read $sock, $buf, $len;
            print "<read $len bytes>\n" if $brief;
            print FH $buf if defined $file;
        };
    }
}

sub usage {
    $0 =~ s|^.*/||;
    my $S = ' ' x length($0);
    print <<USAGE;
Usage: $0 [--host host] [--port port] [--pwd pass]
       $S [--htl n] [--dlen n] [--mlen n]
       $S hello|chk|svk|get|put|diag [<URI>] [<file>|-]
USAGE
}



