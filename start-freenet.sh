#!/bin/sh

# Check to see whether we use echo -n or echo "\c" to suppress newlines.
case "`echo 'hi there\c'; echo ' '`" in
  *c*) n='-n'; c='';;
  *)   n=''; c='\c';;
esac

# and get java implementation too, Sun JDK or Kaffe
JAVA_IMPL=`java -version 2>&1 | head -n 1 | cut -f1 -d' '`

SEEDURL="http://www.freenetproject.org/snapshots/seednodes.ref.bz2"

if test -f lib/freenet-ext.jar ; then
  CLASSPATH=lib/freenet-ext.jar:$CLASSPATH
  echo Detected freenet-ext.jar in lib/
elif test -f freenet-ext.jar ; then
  CLASSPATH=freenet-ext.jar:$CLASSPATH
  echo Detected freenet-ext.jar
else
  echo freenet-ext.jar not found.  It can be downloaded from
  echo http://freenetproject.org/snapshots/freenet-ext.jar
  echo
  echo $n "Would you like me to download it now? [Y/n] $c"
  read resp
  case "$resp" in
    [Nn]*) exit 0;;
  esac
  wget http://freenetproject.org/snapshots/freenet-ext.jar
  if test ! -f freenet-ext.jar; then
    echo "Sorry, I couldn't download it.  Aborting."
    exit 1
  fi
fi

if test -f freenet.jar ; then
  CLASSPATH=freenet.jar:$CLASSPATH
  echo Detected freenet.jar
elif test -f lib/freenet.jar ; then
  CLASSPATH=lib/freenet.jar:$CLASSPATH
  echo Detected freenet.jar in lib/
elif test -f build/freenet/node/Node.class ; then
  CLASSPATH=build/:$CLASSPATH
  echo Detected built tree in build/
else
  echo freenet.jar not found. It can be downloaded from
  echo http://freenetproject.org/snapshots/freenet-latest.jar
  echo
  echo $n "Would you like me to download it now? [Y/n/u] (type u to have me download the unstable jar, otherwise I will fetch the stable jar) $c"
  read resp
  case "$resp" in 
    [Nn]*) exit 0;;
    [Uu]*) wget http://freenetproject.org/snapshots/freenet-unstable-latest.jar -O freenet.jar
    SEEDURL="http://www.freenetproject.org/snapshots/unstable.ref.bz2" ;;
    [Yy]*) wget http://freenetproject.org/snapshots/freenet-latest.jar -O freenet.jar;;
   esac
   if test ! -f freenet.jar; then
     echo "Sorry, I couldn't download it. Aborting."
     exit 1
   fi
fi

CLASSPATH=freenet.jar:freenet-ext.jar:$CLASSPATH 
export CLASSPATH
# why are we permanently altering the environment?
# because bourne shell needs it!


if test ! -f seednodes.ref; then
  echo seednodes.ref not found, would you like to download some seeds
  echo from $SEEDURL ?
  echo $n "[y/N] $c"
  read resp
  case "$resp" in
    [yY]*)
      wget $SEEDURL -O new-seednodes.ref.bz2
      if test -s new-seednodes.ref.bz2; then
	    bzcat new-seednodes.ref.bz2 >seednodes.ref;
	    rm -f new-seednodes.ref.bz2
	  fi
	  ;;
  esac
fi

if test ! -f freenet.conf; then
  echo It appears that this is your first time running Freenet.  You
  echo should read the README file as it contains important instructions
  echo and advice.
  echo
  echo First we must generate a freenet.conf file.  I will now run
  echo Freenet in configure mode, and it will ask you a number of
  echo questions.  If you don\'t understand the question, hitting "enter"
  echo without typing anything will go with the default which is likely
  echo to be the right thing.
  echo
  if test ! -f preconfig.sh; then
    if test -f scripts/preconfig.sh; then
      sh scripts/preconfig.sh
    else
      echo Could not find preconfig.sh or scripts/preconfig.sh
      exit 23
    fi
  else
  sh preconfig.sh
  fi
  java freenet.node.Main --config
fi

# if Sun JDK set -server option as suggested on mailing list
#if java -help 2>&1 | grep "[-]server" >/dev/null ;
#then
#  JAVA_ARGS="-server"
#else
#  JAVA_ARGS=""
#fi
# multiple reports that -server option seems to cause crashes

MEMORY=160

# sun specific options
if [ "$JAVA_IMPL" = "java" ] ; then 
 echo Sun java detected.
 # Tell it not to use NPTL.
 # BAD THINGS happen if it uses NPTL.
 # Specifically, at least on 1.4.1. and 1.5.0b2, we get hangs
 # where many threads are stuck waiting for a lock to be 
 # unlocked but no thread owns it.
if test -f /etc/redhat-release
then
  LD_ASSUME_KERNEL=2.2.5
  export LD_ASSUME_KERNEL
#gentoo however dies with 2.2.5
elif test -f /etc/gentoo-release -o -f /etc/SuSE-release 
then 
	LD_ASSUME_KERNEL=2.4.1 
	export LD_ASSUME_KERNEL
else
  LD_ASSUME_KERNEL=2.2.5
  export LD_ASSUME_KERNEL
fi	
 # 1.4.0?
 SUN_VERSION=`java -version 2>&1 | head -n 1 | sed "s/java version \"\(.*\)\"/\1/"`
 if echo $SUN_VERSION | grep "^1.[0-3]" ; then
  echo Old version of java detected.
  echo Please install a 1.4.x JVM.
  exit
 fi
 if echo $SUN_VERSION | grep "^1.4.0" ; then
  echo Sun java 1.4.0 detected.
  echo Not a good idea. Sun java 1.4.0 has some serious problems relating to NIO, which cause freenet not to run well on it. Please upgrade to a more recent JVM.
  exit
 fi
 if echo $SUN_VERSION | grep "^1.4.1" ; then
  echo Sun java 1.4.1 detected, do not need to specify direct memory limit. OK.
 else
  echo Sun Java 1.4.2 detected.
  if test ! `uname` == "Darwin"; then
	JAVA_ARGS="-XX:MaxDirectMemorySize=${MEMORY}m $JAVA_ARGS"
  fi
 fi
fi 

echo -n "Starting Freenet now: "
echo Command line: java -Xmx${MEMORY}m $JAVA_ARGS freenet.node.Main "$@"
nice -n 10 -- java -Xmx${MEMORY}m $JAVA_ARGS freenet.node.Main "$@" &
echo $! > freenet.pid
echo "Done"
