#!/usr/bin/env bash
### BEGIN INIT INFO
# Provides:          smokeroven
# Required-Start:    $remote_fs $syslog $network
# Required-Stop:     $remote_fs $syslog $network
# Default-Start:     2 3 4 5
# Default-Stop:      0 1 6
# Short-Description: smokeroven
# Description
export HOME=/opt/smokeroven
export PID=${HOME}/bin/.pid

case $1 in
    start)
        start-stop-deamon -b --start --startas /bin/java --name smokeroven --pidfile ${PID} -exec /bin/java -- -Xmx1G -Xms1G -Dspring.configuration.location=${HOME}/config -jar ${HOME}/SmokerOvenApplication.jar
    ;;
    stop)
        start-stop-deamon --stop --name smokeroven --pidfile ${PID} -exec /bin/java --retry=0/30/KILL/5
    ;;
    restart)
        $0 stop
        $0 start
    ;;
    *)
        echo "$0 [start|stop|restart]"
esac
