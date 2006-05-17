#!/bin/sh
cp freenet.jar freenet.jar.old
cp seednodes.ref seednodes.ref.old
wget http://downloads.freenetproject.org/freenet-latest.jar -O freenet-latest.jar
if test -s freenet-latest.jar; then
    mv -f freenet-latest.jar freenet.jar
fi
wget http://downloads.freenetproject.org/seednodes.ref.bz2 -O new-seednodes.ref.bz2
if test -s new-seednodes.ref.bz2; then
    bzcat new-seednodes.ref.bz2 >seednodes.ref
    rm -f new-seednodes.ref.bz2
fi
wget -N http://downloads.freenetproject.org/freenet-ext.jar

touch -t "197001011200" seednodes.ref || touch -d "1/1/1970" seednodes.ref
# so we don't reseed unless necessary
