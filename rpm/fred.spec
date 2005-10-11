Name: fred
Summary: The Freenet Reference Implementation
Version: 0.4.0
Release: 1
Copyright: GPL
Group: Networking/Daemons
Vendor: The Freenet Project
URL: http://www.freenetproject.org/
BuildRoot: /tmp/fred-root

%description
A daemon that transfers and caches data for Freenet: a free, anonymous,
survivable, scalable, and secure data publication network.

%install
mkdir -p $RPM_BUILD_ROOT/etc/init.d     $RPM_BUILD_ROOT/usr/lib \
         $RPM_BUILD_ROOT/var/spool/fred $RPM_BUILD_ROOT/var/log
cp ../fred.init $RPM_BUILD_ROOT/etc/init.d/fred
cp ../../freenet.jar $RPM_BUILD_ROOT/usr/lib/fred.jar
touch $RPM_BUILD_ROOT/var/log/fred $RPM_BUILD_ROOT/etc/fred.conf

%pre
[ "$1" = 1 ] && useradd -M fred

%post
bash -c 'java -cp /usr/lib/fred.jar Freenet.config.Setup --silent /etc/fred.conf.in'
sed 's/storePath=store/storePath=\/var\/spool\/fred/;s/logFile=freenet.log/logFile=\/var\/log\/fred/' /etc/fred.conf.in > /etc/fred.conf
rm /etc/fred.conf.in

%postun
userdel fred

%files
%defattr (-, fred, fred)
%dir /var/spool/fred
/var/log/fred
/usr/lib/fred.jar
/etc/fred.conf
/etc/init.d/fred
