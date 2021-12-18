#!/usr/bin/env bash
#
# Copyright (c) 2021 - for information on the respective copyright owner
# see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
#
# SPDX-License-Identifier: Apache-2.0
#

# Fail, if any command fails
set -e

# Invoke the generator command
echo "Invoking KII run script"
./kii-run.sh

# We need to make sure that the provisioner container gets terminated when the generator container does.
# This will probably become obsolete as soon as the Sidecar KEP has been implemented and we can define the provisioner
# as a sidecar container.

# Wait until the provisioner process has started.
until pidof java
do
    sleep 0.5
done

# Give the provisioner some time to get ready before sending the SIGTERM signal.
sleep 10

# Send SIGTERM to provisioner
provisioner_pid=$(cat "${KII_SHARED_FOLDER}/provisioner.pid")
echo "Sending SIGTERM to provisioner (PID: ${provisioner_pid})"
kill -SIGTERM "${provisioner_pid}"
