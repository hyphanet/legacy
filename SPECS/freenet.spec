Name: freenet
Summary: The Freenet Reference Implementation
Version: 0.5
Release: 1
Copyright: GPL
Group: Networking/Daemons
Vendor: The Freenet Project
URL: http://www.freenetproject.org/
BuildRoot: /tmp/freenet

%description
A daemon that transfers and caches data for Freenet: a free, anonymous,
survivable, scalable, and secure data publication network.

%install
mkdir -p $RPM_BUILD_ROOT/etc/init.d     $RPM_BUILD_ROOT/usr/lib \
         $RPM_BUILD_ROOT/var/spool/freenet $RPM_BUILD_ROOT/var/log \
         $RPM_BUILD_ROOT/usr/share/freenet
cp /tmp/freenet/*.* $RPM_BUILD_ROOT/usr/share/freenet
touch $RPM_BUILD_ROOT/var/log/freenet

%pre
[ "$1" = 1 ] && useradd -M freenet

%post
bash -c 'java -cp /usr/share/freenet/freenet.jar freenet.config.Setup
--silent /etc/freenet.conf.in'
sed
's/storePath=store/storePath=\/var\/spool\/freenet/;s/logFile=freenet.log/logFile=\/var\/log\/fred/'
/etc/freenet.conf.in > /etc/freenet.conf rm /etc/freenet.conf.in
chmod +x /usr/share/freenet/*.sh

%postun
userdel freenet

%files
%defattr (-, freenet, freenet)
%dir /var/spool/freenet
/usr/share/freenet/*.sh
/var/log/freenet
/usr/share/freenet/*.jar
/usr/share/freenet/seednodes.ref
