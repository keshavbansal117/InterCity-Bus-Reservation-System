#!/bin/sh
cd "$(dirname "$0")"
mkdir -p out
javac -cp "lib/*" -sourcepath src -d out src/com/firstchoice/busreservation/web/WebApp.java || exit 1
: "${ADMIN_PASSWORD:=Admin@12345}"; export ADMIN_PASSWORD
echo "Website: http://localhost:8080   Admin (local only): admin@firstchoice.local / $ADMIN_PASSWORD"
java -cp "out:lib/*" com.firstchoice.busreservation.web.WebApp
