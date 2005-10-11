#!/bin/sh
#
# refresh_bookmarks.sh
#
# Keep freenet bookmarks refreshed
#
# Author: dolphin
#
# Usage: ./refresh_bookmarks.sh
#
# Put this script in your main freenet dir and run from there
# (expects to find freenet.conf and lib/freenet.jar in current dir)

# Uncomment the following line to skip searching the local store
# (better for improving your node's routing), or comment it out
# to use the local store
skipDS='--skipDS'

logLevel=error		#adjust to taste

cd $(realpath $(dirname $0))

HTL=$(awk -F'=' '/maxHopsToLive=/ {print $2}' freenet.conf)
SleepOnFail=$(($(awk -F'=' '/failureTableTime=/ {print $2}' freenet.conf) / 500))
SleepOnSuccess=$((SleepOnFail / 2))

for key in $(awk -F'=' '/bookmarks\..*\.key=/ {print $2}' freenet.conf | sort -u)
do
	{ 	while :
		do
			echo "Refreshing $key"
			if java -cp lib/freenet.jar freenet.client.cli.Main get $key \
				--htl ${HTL} ${skipDS} --logLevel ${logLevel}
			then
				sleep ${SleepOnSuccess}
			else
				sleep ${SleepOnFail}
			fi
		done
	} &	#don't remove this ampersand here!  Not a typo!  :-)

	sleep 600	#wait 10 minutes before invoking next instance
done &
