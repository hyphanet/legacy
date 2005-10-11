#!/bin/sh
#
# addnodes.sh
#
# Add nodes from current routing table to seednodes.ref
#
# Revised to be as bulletproof as possible :-)
#
# Author: dolphin
#
# Usage: ./addnodes.sh minConnections
#
# Put this script in your main freenet dir and run from there
# (expects to find noderefs.txt, seednodes.ref and dedupe.sh
# in current dir)
#
# Requires: wget, dedupe.sh

# function usage()

usage()
{
	echo "usage: addnodes.sh minConnections"
}

# function: abort()
#
# error exit

abort()
{
	echo "Aborting" >&2
	rm -f addnodes.pid seednodes.ref.new*
	exit 1
}

# function restore_noderefs()
#
# restore noderefs.txt from backup

restore_noderefs()
{
	if ! mv noderefs.txt.bak noderefs.txt
	then
		echo "Yikes!  Panic time!  Error occurred while restoring noderefs.txt!" >&2
	fi
}

# function restore_seednodes()
#
# restore seednodes.ref from backup

restore_seednodes()
{
	if ! mv seednodes.ref.bak seednodes.ref
	then
		echo "Yikes!  Panic time!  Error occurred while restoring seednodes.ref!" >&2
	fi
}

# Main

cd $(realpath $(dirname $0))

# check for another instance already running 
if [ -f addnodes.pid ]
then
	pid=$(cat addnodes.pid)
	if ps xww -p $pid 2>/dev/null | grep -q "addnodes.sh"
	then
		echo "addnodes.sh is already running, exiting" >&2
		exit 1
	fi
fi

if [ $# -ne 1 ]
then
	usage
	exit 1
fi

echo $$ > addnodes.pid

# Read minCP from freenet.conf
minCP=$(awk -F'=' '/^minCP=/ {print $2}' freenet.conf)

# minConnections is our command line parameter
minConnections=$1


if [ ! -f noderefs.txt ]
then
	echo "You don't seem to have a noderefs.txt file" >&2
	echo "That's OK, we'll go ahead and make a new one" >&2
	
	# Create a new, empty file
	touch noderefs.txt || abort
fi

echo "Backing up noderefs.txt"

if ! cp -p noderefs.txt noderefs.txt.bak
then
	echo "Error occurred while backing up noderefs.txt" >&2
	abort
fi

echo "Fetching noderefs.txt, please wait..."

if ! wget -q -O noderefs.txt "http://localhost:8888/servlet/nodestatus/noderefs.txt?minCP=${minCP}&minConnections=${minConnections}"
then
	echo "Error occurred while fetching noderefs.txt, restoring old noderefs.txt" >&2
	restore_noderefs
	abort
fi

if [ ! -f seednodes.ref ]
then
	echo "You don't seem to have a seednodes.ref file" >&2
	echo "That's OK, we'll go ahead and make a new one" >&2

	# Create a new, empty file
	touch seednodes.ref || abort
fi

echo "Backing up seednodes.ref"

if ! cp seednodes.ref seednodes.ref.bak
then
	echo "Error occurred while backing up seednodes.ref" >&2
	abort
fi

echo "Creating combined noderefs/seednodes file in seednodes.ref.new"

if ! cat noderefs.txt seednodes.ref > seednodes.ref.new
then
	echo "Error occurred while creating seednodes.ref.new" >&2
	abort
fi

echo "Running dedupe.sh on seednodes.ref.new"

if ! ./dedupe.sh seednodes.ref.new
then
	echo "Error occurred while deduping seednodes.ref.new, restoring old seednodes.ref" >&2
	restore_seednodes
	abort
fi

echo "Moving deduped seednodes.ref.new to seednodes.ref"

if ! mv seednodes.ref.new seednodes.ref
then
	echo "Error occurred while moving deduped seednodes.ref.new to seednodes.ref, restoring old seednodes.ref" >&2
	restore_seednodes
	abort
fi

echo "Done!"

rm -f addnodes.pid seednodes.ref.new.bak
exit 0
