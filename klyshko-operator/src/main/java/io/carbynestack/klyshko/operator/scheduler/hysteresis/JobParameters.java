/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import io.carbynestack.castor.common.entities.TupleType;
import io.etcd.jetcd.ByteSequence;

import java.nio.charset.StandardCharsets;

public record JobParameters(TupleType tupleType) {

    public JobParameters(ByteSequence value) throws IllegalArgumentException {
        this(TupleType.valueOf(value.toString(StandardCharsets.UTF_8)));
    }

    public ByteSequence toByteSequence() {
        return ByteSequence.from(tupleType.name(), StandardCharsets.UTF_8);
    }

}
