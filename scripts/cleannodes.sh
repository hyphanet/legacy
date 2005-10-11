#!/bin/sh
#
# cleannodes.sh
#
# Remove a range of node references (versions) from input file
#
# Author: dolphin
#
# Usage: cleannodes.sh infile minExclude maxExclude

if [ $# -ne 3 ]
then
	echo "Usage: cleannodes.sh infile minExclude maxExclude"
	exit 1
fi

infile=$1
outfile=/tmp/$(basename $infile).$$

minExclude=$2
maxExclude=$3

if [ $minExclude -gt $maxExclude ]
then
	echo "Minimum exclude version must be <= maximum"
	exit 1
fi

cp $infile ${infile}.bak	#always backup!  :-)
rm -f $outfile

# show version stats for input file
echo
echo "Input versions (file $infile):"
grep ^version $infile | sort | uniq -c
echo

#function: read a single noderef from input, save to temp file
read_one_noderef()
{
	rm -f /tmp/noderef.$$
	
	while read line
	do
		echo $line >> /tmp/noderef.$$
		if [ "$line" = "End" ]
		then
			break
		fi
	done
}

while :
do
	read_one_noderef

	if [ ! -f /tmp/noderef.$$ ]
	then
		break	#end of file
	fi
	
	version=$(awk -F',' '/^version=/ {print $4}' /tmp/noderef.$$)

	echo -n version $version

	if [ $version -ge $minExclude -a $version -le $maxExclude ]
	then
		echo ", discarding node"
		rm -f /tmp/noderef.$$
	else
		echo ", keeping node"
		cat /tmp/noderef.$$ >> $outfile
	fi
done < $infile

mv $outfile $infile

# show version stats for output file
echo
echo "Output versions (file $infile):"
grep ^version $infile | sort | uniq -c
echo

rm -f /tmp/noderef.$$
