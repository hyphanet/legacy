# $Id: Makefile,v 1.42.4.4.2.5 2004/07/22 17:48:05 amphibian Exp $

SHELL ?= /bin/sh
RELEASE_NAME ?= `date +%Y%m%d`
JAR ?= jar

#
# define build targets
#

CLIENT:=freenet/client/*.java \
	freenet/client/cli/*.java

FPROXY:=freenet/client/http/*.java \
	freenet/client/http/filter/*.java

TOOLS:=freenet/interfaces/servlet/TestHttpServlet.java \
       freenet/node/NodeConsole.java 

NODE:=freenet/node/Main.java \
      freenet/interfaces/servlet/SingleHttpServletContainer.java \
      freenet/interfaces/servlet/MultipleHttpServletContainer.java \
      freenet/node/http/DiagnosticsServlet.java \
      freenet/node/http/ColoredPixelServlet.java \
      freenet/node/http/DistributionServlet.java \
      freenet/node/http/NodeInfoServlet.java \
      freenet/node/http/BookmarkManagerServlet.java \
      freenet/node/rt/RoutingPointStore.java \
      freenet/node/rt/HistoryKeepingRoutingPointStore.java

TESTS:=freenet/crypt/CryptTest.java \
       freenet/diagnostics/DiagnosticsTest.java \
       freenet/support/sort/SortTest.java \
       freenet/support/test/FieldsTest.java \
       freenet/support/test/HeapTest.java \
       freenet/support/test/KeyListTest.java \
       freenet/support/test/RedBlackTreeTest.java \
       freenet/support/test/BoyerMooreTest.java \
       freenet/support/test/URLDecoderTest.java \
       freenet/node/http/DistributionTest.java

#
# detect java compiler
#

JAVAC_OPTS:=-sourcepath src -d build $(JAVAC_OPTS)

ifndef JAVAC
    JAVAC:=$(shell if which jikes >/dev/null; \
		       then echo jikes; \
		       else if which kaffe >/dev/null; \
			   then echo kaffe at/dms/kjc/Main; \
			   else echo javac; \
		       fi \
		   fi)
endif

ifeq ($(shell test -f lib/freenet-ext.jar && echo yes), yes)
    JAVAC_CPATH:=$(strip $(JAVAC_CPATH) lib/freenet-ext.jar)
endif

ifeq ($(shell test -f lib/junit.jar && echo yes), yes)
    JAVAC_CPATH:=$(strip $(JAVAC_CPATH) lib/junit.jar)
endif

empty:=
space:= $(empty) $(empty)
JAVAC_CPATH:=$(subst $(space),:,$(JAVAC_CPATH))

ifneq ($(JAVAC_CPATH),)
    ifeq ($(JAVAC), jikes)
	JAVAC_OPTS:=-bootclasspath $(JAVAC_CPATH) $(JAVAC_OPTS)
    else
	JAVAC_OPTS:=-classpath $(JAVAC_CPATH) $(JAVAC_OPTS)
    endif
endif

# set JAVA to java2 to compile without 1.1 compatability
ifneq ($(JAVA), java2)
ifeq ($(JAVAC), javac)
	JAVAC_OPTS:=$(shell java -version 2>&1 | grep '1\.1' >/dev/null \
		      || echo -target 1.1) \
		      $(JAVAC_OPTS)
endif		
else
#  JAVAC_OPTS:=-deprecation $(JAVAC_OPTS)
endif

#
# targets
#

all: client fproxy tools node

clean:
	rm -rf lib/freenet.jar build/freenet binfred *.o

realclean: clean
	rm -rf lib build tgz node_* store_* seednodes.ref freenet.log java gnu

check: tests
	scripts/test.sh

lib:
	mkdir lib

build:
	mkdir build

#
# download targets
#

getlibs: lib
	rm -f lib/freenet-ext.jar
	cd lib; wget http://freenetproject.org/snapshots/freenet-ext.jar

getseeds:
	rm -f seednodes.ref
	wget http://freenetproject.org/snapshots/seednodes.ref

#
# dist targets
#

jar: all lib
	cd build; \
	$(JAR) -cmf ../src/node.manifest ../lib/freenet.jar freenet

tgz: jar
	mkdir -p tgz/freenet-$(RELEASE_NAME)
	cp -R lib tgz/freenet-$(RELEASE_NAME)
	cp start-freenet*.sh stop-freenet.sh scripts/preconfig.sh README tgz/freenet-$(RELEASE_NAME)
	if test -f seednodes.ref; then cp seednodes.ref tgz/freenet-$(RELEASE_NAME); fi
	cd tgz ;\
	tar c freenet-$(RELEASE_NAME) | gzip > ../freenet-$(RELEASE_NAME).tgz
	rm -rf tgz/freenet-$(RELEASE_NAME)

#rpm: jar
#	mkdir -p rpm/BUILD rpm/RPMS
#	java -cp lib/freenet.jar freenet.config.Setup --silent fred.conf.in
#	sed 's/^storePath=.*$/storePath=\/var\/spool\/fred/;\
#	     s/^logFile=.*$/logFile=\/var\/log\/fred/'\
#	     fred.conf.in > rpm/fred.conf
#	rm fred.conf.in
#	echo "%_topdir rpm" > rpm/.rpmmacros
#	HOME=rpm rpm -bb --target=noarch rpm/fred.spec
#	mv rpm/RPMS/noarch/fred*.noarch.rpm .
#	rm -rf rpm/BUILD rpm/RPMS rpm/.rpmmacros

# TARGET gcj 
# compiles a binary fred (named binfred) from bytecode freenet.jar

gcj: binfred

binfred: jar
	CLASSPATH=lib/freenet-ext.jar \
	gcj --main=freenet.node.Main \
		-o binfred \
		lib/freenet.jar \
		lib/freenet-ext.jar

#
# compilation targets
#

client fproxy tools node tests:: build

client:: $(CLIENT)
	$(JAVAC) $(JAVAC_OPTS) $(addprefix src/,$^)

fproxy:: $(FPROXY)
	$(JAVAC) $(JAVAC_OPTS) $(addprefix src/,$^)
	test -d build/freenet/node/http/templates || mkdir -p build/freenet/node/http/templates
	cp -r src/freenet/node/http/templates build/freenet/node/http/

tools:: $(TOOLS)
	$(JAVAC) $(JAVAC_OPTS) $(addprefix src/,$^)

node:: $(NODE)
	$(JAVAC) $(JAVAC_OPTS) $(addprefix src/,$^)

tests:: $(TESTS)
	$(JAVAC) $(JAVAC_OPTS) $(addprefix src/,$^)


%.java:
	


.PHONY: all clean check getlibs getseeds jar tgz \
	client fproxy tools node tests %.java
