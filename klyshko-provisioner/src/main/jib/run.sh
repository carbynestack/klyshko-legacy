#!/bin/bash
#
# Copyright (c) 2021 - for information on the respective copyright owner
# see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
#
# SPDX-License-Identifier: Apache-2.0
#

# Fail, if any command fails
set -e

# Signal handler to terminate main java process by sending SIGTERM signal but exit with 0 exit code
terminate_java() {
  echo "Killing provisioner java process (PID: $1)"
  kill -SIGTERM "$1"

  # Ignore java process SIGTERM exit code
  set +e
  wait "$1"
  set -e
  exit_code=$?
  if [[ ${exit_code} -eq 143 ]]; then exit_code=0; fi
  exit ${exit_code}
}

# Run main java process
java --enable-preview -jar quarkus-run.jar &
java_pid=$!

# Writing PID of this process to file to allow main container to send SIGTERM on termination
pid=$$
pid_file="${KII_SHARED_FOLDER}/provisioner.pid"
echo "${pid}" > "${pid_file}"
echo "Process ID (${pid}) of provisioner main process written to file ${pid_file}"

# Register SIGTERM signal handler
trap "terminate_java $java_pid" TERM

# Wait until java process terminates
wait $java_pid
