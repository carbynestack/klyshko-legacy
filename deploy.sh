#!/usr/bin/env bash
#
# Copyright (c) 2021 - for information on the respective copyright owner
# see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
#
# SPDX-License-Identifier: Apache-2.0
#

kubectl config use-context "kind-apollo"
helm repo add bitnami https://charts.bitnami.com/bitnami
helm install test-etcd --set auth.rbac.enabled=false --set service.type=LoadBalancer bitnami/etcd

(
cd klyshko-provisioner
./deploy.sh
)
(
cd klyshko-mp-spdz
./deploy.sh
)
(
cd klyshko-operator
./deploy.sh
)