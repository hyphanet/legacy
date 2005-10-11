#!/bin/sh
#
# versions.sh
#
# Show a count of the number of each unique node version in input file
#
# Author: dolphin
#
# Usage: versions.sh inputfile

if [ $# -ne 1 ]
then
	echo "Usage: versions.sh inputfile"
	exit 1
fi

grep ^version $1 | sort | uniq -c
