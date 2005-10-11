#!/bin/sh
#
# dedupe.sh
#
# Dedupe nodes in input file (e.g. seednodes.ref or noderefs.txt)
#
# Revised to be as bulletproof as possible :-)
#
# Author: dolphin
#
# Usage: ./dedupe.sh inputfile
#
# Put this script in your main freenet dir and run from there
# (companion script addnodes.sh expects to find it in current dir)
#
# Algorithm:
#
# Step 1:	Backup input file!  :-)
#
# Step 2:	Read noderef records one at a time from input file,
#			saving version info to a temp file for each unique IP
#
# Step 3:	Remove all but the highest version number in each IP's
#			temp file
#
# Step 4:	Reread the input file one record at a time as above,
#			saving only the first node whose version matches the one
#			in its IP's temp file, discarding all others, writing
#			output to a temporary nodes file
#
# Step 5:	Move temporary nodes file to original input file
#
#			Not terribly elegant, but it gets the job one  :-)
#
# One little bugaboo: If a node has only multiple references to a single version,
#			the first one found is saved, all others are deleted.  Didn't know how
#			else to handle this without more info to go on.  :-)

if [ $# -ne 1 ]
then
	echo "Usage: ./dedupe.sh infile" >&2
	exit 1
fi

# function: abort()
#
# error exit

abort()
{
	echo "$1" >&2
	echo "Aborting" >&2
	rm -f $outfile
	# use find instead of "rm /tmp/node*" to avoid "too many arguments" error
	find /tmp -maxdepth 1 -type f -name "node*" | xargs rm -f
	exit 1
}

# function: read_one_noderef()
#
# read a single noderef from input, save to temp file

read_one_noderef()
{
	rm -f /tmp/noderef.$$
	
	while read line
	do
		echo $line >> /tmp/noderef.$$ || abort "Error occurred while writing to /tmp/noderef.$$"
		if [ "$line" = "End" ]
		then
			break
		fi
	done
}

infile=$1
outfile=/tmp/$(basename $infile).$$
rm -f $outfile
# use find instead of "rm /tmp/node*" to avoid "too many arguments" error
find /tmp -maxdepth 1 -type f -name "node*" | xargs rm -f

if [ ! -f $infile ]
then
	echo "$infile doesn't seem to exist, exiting." >&2
	exit 1
fi

echo "Backing up $infile"

cp -p $infile $infile.bak || abort "Error occurred while backing up $infile"

echo "Collecting node version info..."

i=0

while :
do
	read_one_noderef

	[ ! -f /tmp/noderef.$$ ] && break	#end of file
	
	physical_tcp=$(awk -F'=' '/^physical.tcp=/ {print $2}' /tmp/noderef.$$)
	clean_tcp=$(echo $physical_tcp | md5sum | cut -b 1-8)
	version=$(awk -F',' '/^version=/ {print $4}' /tmp/noderef.$$)

	echo $version >> /tmp/node.$clean_tcp || abort "Error occurred while writing to /tmp/node.$clean_tcp"
	
	i=$((i + 1))
done < $infile

echo "$i node references found in $infile"

echo "Determining latest version of each node..."

for f in /tmp/node.*
do
	sort -urn -o $f.tmp $f
	head -n 1 $f.tmp > $f
done

echo "Deduping..."

i=0

while :
do
	read_one_noderef

	[ ! -f /tmp/noderef.$$ ] &&	break	#end of file
	
	physical_tcp=$(awk -F'=' '/^physical.tcp=/ {print $2}' /tmp/noderef.$$)
	clean_tcp=$(echo $physical_tcp | md5sum | cut -b 1-8)
	version=$(awk -F',' '/^version=/ {print $4}' /tmp/noderef.$$)

	if grep -q $version /tmp/node.$clean_tcp
	then
		i=$((i + 1))
		cat /tmp/noderef.$$ >> $outfile || abort "Error occurred while writing to $outfile"
	fi
		
	# don't allow any more of this node in output
	cat /dev/null > /tmp/node.${clean_tcp} || abort "Error occurred while writing to /tmp/node.$clean_tcp"
done < $infile

if ! mv $outfile $infile
then
	echo "Error occurred while saving deduped $infile, restoring old $infile from backup" >&2
	mv $infile.bak $infile || echo "Yikes!  Panic time!  Error occurred while restoring $infile!" >&2
	abort
fi

echo "Saved $i unique nodes to $infile"

# use find instead of "rm /tmp/node*" to avoid "too many arguments" error
find /tmp -maxdepth 1 -type f -name "node*" | xargs rm -f
 
exit 0
