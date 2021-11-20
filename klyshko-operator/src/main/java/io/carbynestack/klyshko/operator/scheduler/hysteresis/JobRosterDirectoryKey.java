/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import io.etcd.jetcd.ByteSequence;

import java.util.Optional;

public record JobRosterDirectoryKey() implements Key {

    public static final JobRosterDirectoryKey INSTANCE = new JobRosterDirectoryKey();

    @Override
    public String toString() {
        return "/jobs";
    }

    @Override
    public Optional<ByteSequence> toEtcdParentKey() {
        return Optional.empty();
    }

}
