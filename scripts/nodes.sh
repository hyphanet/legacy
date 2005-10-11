#!/bin/sh
#
# Displays IP and version info for each node reference in a file
#
# Author: dolphin
#
# Usage: nodes.sh infile

if [ $# -ne 1 ]
then
	echo "Usage: nodes.sh infile"
	exit 1
fi

awk '
BEGIN {
	i=0
}
END {
	printf("\n%d nodes found\n", i)
}

/^physical.tcp=/ {
		split($0, addr, "[=:]")
}
/^version=/ {
		split($0, vers, ",")
		print addr[2] ":" addr[3] "\t" vers[4]
		++i
}' $1
