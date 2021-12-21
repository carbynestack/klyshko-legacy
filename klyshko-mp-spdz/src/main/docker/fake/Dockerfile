#
# Copyright (c) 2021 - for information on the respective copyright owner
# see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
#
# SPDX-License-Identifier: Apache-2.0
#
ARG PROJECT_VERSION
FROM carbynestack/klyshko-mp-spdz:${PROJECT_VERSION}-mp-spdz-base

# Build fake offline phase executable
COPY CONFIG.mine .
RUN make -j $(nproc) tldr
RUN make -j $(nproc) Fake-Offline.x

# Copy resources
COPY kii-run.sh .
