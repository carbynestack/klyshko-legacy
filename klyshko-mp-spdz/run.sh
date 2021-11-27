#!/usr/bin/env bash
#
# Copyright (c) 2021 - for information on the respective copyright owner
# see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
#
# SPDX-License-Identifier: Apache-2.0
#

n=${KII_TUPLES_PER_JOB}
pn=${KII_PLAYER_NUMBER}
declare -A argsByType=(
  ["bit_gfp"]="--nbits 0,${n}"
  ["bit_gf2n"]="--nbits ${n},0"
  ["inputmask_gfp"]="--ntriples 0,${n}"
  ["inputmask_gf2n"]="--ntriples ${n},0"
  ["inversetuple_gfp"]="--ninverses ${n}"
  ["inversetuple_gf2n"]="--ninverses ${n}"
  ["squaretuple_gfp"]="--nsquares 0,${n}"
  ["squaretuple_gf2n"]="--nsquares ${n},0"
  ["multiplicationtriple_gfp"]="--ntriples 0,${n}"
  ["multiplicationtriple_gf2n"]="--ntriples ${n},0"
)
declare -A folderByType=(
  ["bit_gfp"]="2-p-128/Bits-p-P${pn}"
  ["bit_gf2n"]="2-2-128/Bits-2-P${pn}"
  ["inputmask_gfp"]="2-p-128/Triples-p-P${pn}"
  ["inputmask_gf2n"]="2-2-128/Triples-2-P${pn}"
  ["inversetuple_gfp"]="2-p-128/Inverses-p-P${pn}"
  ["inversetuple_gf2n"]="2-2-128/Inverses-2-P${pn}"
  ["squaretuple_gfp"]="2-p-128/Squares-p-P${pn}"
  ["squaretuple_gf2n"]="2-2-128/Squares-2-P${pn}"
  ["multiplicationtriple_gfp"]="2-p-128/Triples-p-P${pn}"
  ["multiplicationtriple_gf2n"]="2-2-128/Triples-2-P${pn}"
)

cmd="./Fake-Offline.x -d 0 --prngseed ${KII_JOB_ID} ${argsByType[${KII_TUPLE_TYPE}]} ${KII_PLAYER_COUNT}"
eval "$cmd"
cp "Player-Data/${folderByType[${KII_TUPLE_TYPE}]}" "/kii/tuples"

# TODO: Move this into scheduler
# The following will become obsolete as soon as sidecar KEP has been implemented.
# Wait until the provisioner process has started, to be able to send signal.
sleep 10
until pids=$(pidof java)
do
    sleep 0.5
done
pkill -SIGTERM java