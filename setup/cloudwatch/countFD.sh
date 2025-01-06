#!/bin/bash
# Check the number of open file descriptors for a specific process
PROCESS_NAME="/opt/traccar/jre/bin/java"  # Replace with your process name
PID=$(pgrep -f "$PROCESS_NAME")  # Get PID of the process
if [ -z "$PID" ]; then
    echo "0"
else
    FD_COUNT=$(ls /proc/$PID/fd | wc -l)
    echo $FD_COUNT
fi
