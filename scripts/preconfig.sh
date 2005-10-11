#!/bin/sh
# This file will place some useful values in freenet.conf prior to running
# freenet.node.Main --config

echo > freenet.conf

# DO NOT predetermine IP address
# We often get it wrong, and the node can autodetect it anyway

if [ "$RANDOM" ]
then
let DEFLP=$RANDOM%30000+2000
else
echo "no random in shell, enter a FNP port number + <ENTER>"
read DEFLP
fi

echo listenPort=$DEFLP >> freenet.conf

cat <<-EOF >> freenet.conf
seedNodes=seednodes.ref

EOF

