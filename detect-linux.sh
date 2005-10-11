#!/bin/sh
if ([ `uname` = 'Linux' ]); then
  echo Linux detected;
  REVISION=`uname -r`;
  export REVISION;
  if (echo $REVISION | grep "2\.[6-9]" > /dev/null) ||
     (echo $REVISION | grep "3\." > /dev/null); then
        echo Linux 2.6+ detected;
  fi
fi
