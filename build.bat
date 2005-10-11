@echo off
rem -- If you change this batchfile, please make sure it
rem -- works on Windows NT/2000 before checking it back in.

echo Building Freenet Java implementation (Fred)

if %OS%_==Windows_NT_ goto a
deltree /Y build
goto b
:a
rem -- there is no `deltree /Y` in NT, use `rd /s /q`
rem -- >nul: quiets the error if it can't be found (normal)
rd /s /q build 2> nul:
:b

mkdir build

echo %CLASSPATH%|find ";src;lib\freenet-ext.jar">nul
if errorlevel 1 set CLASSPATH=%CLASSPATH%;src;lib\freenet-ext.jar

if exist lib\freenet-ext.jar goto haveext
echo freenet-ext.jar not found:
echo Please download http://freenetproject.org/snapshots/freenet-ext.jar
echo into the lib directory.
goto end
:haveext

echo Building the Freenet node and servlets...
javac -target 1.1 -d build src\freenet\node\Main.java src\freenet\interfaces\servlet\SingleHttpServletContainer.java src\freenet\interfaces\servlet\MultipleHttpServletContainer.java src\freenet\node\http\DiagnosticsServlet.java src\freenet\node\http\DistributionServlet.java src\freenet\node\http\NodeInfoServlet.java src\freenet\node\http\BookmarkManagerServlet.java
echo Built node and servlets

echo Building Freenet command-line client...
javac -target 1.1 -d build src\freenet\client\*.java src\freenet\client\cli\*.java
echo Built command-line client.

rem -- There is no choice command in Windows NT/2000, just assume the defaults
if %OS%_==Windows_NT_ goto FProxy_build
choice /C:YN /T:N,5 Should FProxy client be built
If errorlevel == 2 goto builtFProxy

:FProxy_build
echo Building FProxy...
javac -target 1.1 -d build src\freenet\client\http\*.java src\freenet\client\http\filter\*.java src\freenet\support\StripedBucketArray.java
echo Built FProxy
:builtFProxy

echo Copying Templates
md build\freenet\node\http\templates
xcopy /s src\freenet\node\http\templates build\freenet\node\http\templates>nul

echo Building Tools...
javac -target 1.1 -d src\freenet\interfaces\servlet\TestHttpServlet.java src\freenet\node\NodeConsole.java src\freenet\fs\dir\FSConsole.java src\freenet\node\ds\DSConsole.java src\freenet\node\FSTool.java
rem src\freenet\node\rt\RTConsole.java
echo Built Tools

if %2_==tests_ goto build_tests
if %OS%_==Windows_NT_ goto built_tests
choice /C:YN /T:N,5 Should unit tests be built
If errorlevel == 2 goto built_tests

:build_tests
echo Building unit tests...
javac -target 1.1 -d build src\freenet\crypt\CryptTest.java src\freenet\diagnostics\DiagnosticsTest.java src\freenet\support\sort\SortTest.java src\freenet\support\test\FieldsTest.java src\freenet\support\test\HeapTest.java src\freenet\support\test\KeyListTest.java src\freenet\support\test\RedBlackTreeTest.java src\freenet\support\test\BoyerMooreTest.java src\freenet\support\test\URLDecoderTest.java src\freenet\node\http\DistributionTest.java
echo Built unit tests
:built_tests

if %OS%_==Windows_NT_ goto jar_build
choice /C:NY /T:Y,5 Should freenet.jar be created
If errorlevel == 2 goto jar_build
goto builtjar

:jar_build
echo Creating freenet.jar...
jar cmf src\node.manifest lib\freenet.jar -C build .
if %OS%_==Windows_NT_ goto c
deltree /Y build
goto d
:c
rd /s /q build
:d
:builtjar

:end
echo Done
