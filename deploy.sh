#!/usr/bin/env bash

sbt assembly
scp target/scala-2.12/scrappy-assembly-0.0.1-SNAPSHOT.jar appManager@"${SCRAPPY_DEPLOY_SERVER}":/opt/prod
ssh -t appManager@"${SCRAPPY_DEPLOY_SERVER}" "systemctl stop scrappy && systemctl start scrappy && sleep 5 && systemctl status scrappy"