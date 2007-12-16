#!/bin/sh

# Some variables

MEMORY=256m
EXTJAR="freenet-ext.jar"
MAINJAR="freenet.jar"
SEED="seednodes.ref"
SEEDBZ2="$SEED.bz2"

# URLs

DOWNLOADURL="http://downloads.freenetproject.org"
EXTURL="$DOWNLOADURL/$EXTJAR"
MAINURL="$DOWNLOADURL/freenet-stable-latest.jar"
SEEDURL="$DOWNLOADURL/seednodes/$SEEDBZ2"

# Scripts

PRECONFIG="preconfig.sh"

# Check to see whether we use echo -n or echo "\c" to suppress newlines.
case "`echo 'hi there\c'; echo ' '`" in
  *c*) n='-n'; c='';;
  *)   n=''; c='\c';;
esac

# and get java implementation too, Sun JDK or Kaffe
JAVA_IMPL=`java -version 2>&1 | head -n 1 | cut -f1 -d' '`


if test -f lib/"$EXTJAR" ; then
  CLASSPATH=lib/"$EXTJAR":$CLASSPATH
  echo Detected $EXTJAR in lib/
elif test -f "$EXTJAR" ; then
  CLASSPATH="$EXTJAR":$CLASSPATH
  echo Detected $EXTJAR
else
  echo $EXTJAR not found.  It can be downloaded from
  echo $EXTURL
  echo
  echo $n "Would you like me to download it now? [Y/n] $c"
  read resp
  case "$resp" in
    [Nn]*) exit 0;;
  esac
  wget "$EXTURL"
  if test ! -f "$EXTJAR" ; then
    echo "Sorry, I couldn't download it.  Aborting."
    exit 1
  fi
fi

if test -f "$MAINJAR" ; then
  CLASSPATH="$MAINJAR":$CLASSPATH
  echo Detected $MAINJAR
elif test -f lib/"$MAINJAR" ; then
  CLASSPATH=lib/"$MAINJAR":$CLASSPATH
  echo Detected $MAINJAR in lib/
elif test -f build/freenet/node/Node.class ; then
  CLASSPATH=build/:$CLASSPATH
  echo Detected built tree in build/
else
  echo $MAINJAR not found. It can be downloaded from
  echo $MAINURL
  echo
  echo $n "Would you like me to download it now? [Y/n] $c"
  read resp
  case "$resp" in 
    [Nn]*) exit 0;;
    [Yy]*) wget "$MAINURL" -O "$MAINJAR";;
   esac
   if test ! -f "$MAINJAR" ; then
     echo "Sorry, I couldn't download it. Aborting."
     exit 1
   fi
fi

CLASSPATH="$MAINJAR":"$EXTJAR":$CLASSPATH 
export CLASSPATH
# why are we permanently altering the environment?
# because bourne shell needs it!


if test ! -f "$SEED" ; then
  echo $SEED not found, would you like to download some seeds
  echo from $SEEDURL ?
  echo $n "[y/N] $c"
  read resp
  case "$resp" in
    [yY]*)
      wget $SEEDURL -O new-"$SEEDBZ2"
      if test -s new-"$SEEDBZ2" ; then
	    bzcat new-"$SEEDBZ2" > "$SEED";
	    rm -f new-"$SEEDBZ2"
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
  if test ! -f "$PRECONFIG" ; then
    if test -f scripts/"PRECONFIG" ; then
      sh scripts/"$PRECONFIG"
    else
      echo Could not find $PRECONFIG or scripts/$PRECONFIG
      exit 23
    fi
  else
  sh $PRECONFIG
  fi
  java freenet.node.Main --config
fi

# Try with -server once again

if java -help 2>&1 | grep "[-]server" >/dev/null ; then
	JAVA_ARGS="-server"
else
	JAVA_ARGS=""
fi

# sun specific options
if [ "$JAVA_IMPL" = "java" ] ; then 
	echo Sun java detected.
	# check for bad versions
	SUN_VERSION=`java -version 2>&1 | head -n 1 | sed "s/java version \"\(.*\)\"/\1/"`
	if echo $SUN_VERSION | grep "^1.[0-3]" ; then
		echo Old version of java detected.
		echo Please install a 1.4.x or higher JVM.
		exit
	fi
	if echo $SUN_VERSION | grep "^1.4.[01]" ; then
		echo Sun java 1.4.0 or 1.4.1 detected.
		echo Not a good idea. Sun java 1.4.0 has some serious problems relating to
		echo NIO, which cause freenet not to run well on it. Sun java 1.4.1 has
		echo problems with NPTL. Please upgrade to a more recent JVM.
		exit
	fi
	if echo $SUN_VERSION | grep "^1.5.0b2" ; then
		echo Sun java 1.5.0b2 detected. Not a good idea. This version has problems
		echo with NPTL.
		exit
	else
		echo Sun Java 1.4.2 or higher detected. Good.
	fi
fi

echo "Starting Freenet now: "
JAVA_ARGS="-Xmx$MEMORY $JAVA_ARGS freenet.node.Main"
echo Command line: java $JAVA_ARGS "$@"
nice -n 10 -- java $JAVA_ARGS "$@" &
echo $! > freenet.pid
echo "Done"
