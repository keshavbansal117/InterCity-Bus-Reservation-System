#!/bin/sh
cd "$(dirname "$0")"
mkdir -p out
javac -sourcepath src -d out src/com/firstchoice/busreservation/web/WebApp.java || exit 1
echo "Open http://localhost:8080 in your browser"
java -cp out com.firstchoice.busreservation.web.WebApp
