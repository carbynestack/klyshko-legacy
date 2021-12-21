#
# Copyright (c) 2021 - for information on the respective copyright owner
# see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
#
# SPDX-License-Identifier: Apache-2.0
#
ARG PROJECT_VERSION
FROM carbynestack/klyshko-mp-spdz:${PROJECT_VERSION}-mp-spdz-base

# Build cowgear offline phase executable
COPY CONFIG.mine .
COPY Makefile.patch .
RUN cat Makefile.patch >> Makefile
RUN make -j $(nproc) tldr
COPY klyshko-cowgear-offline.cpp Utils/
RUN make -j $(nproc) klyshko-cowgear-offline.x

# Copy resources
COPY kii-run.sh .
