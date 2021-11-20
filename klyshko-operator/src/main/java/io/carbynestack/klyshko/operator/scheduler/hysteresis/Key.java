/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import io.etcd.jetcd.ByteSequence;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

public sealed interface Key permits JobRosterDirectoryKey, JobRosterKey, JobRosterEntryKey {

    static <T extends Key> T fromEtcdKey(ByteSequence key, Class<T> keyType) throws IllegalArgumentException {
        try {
            return keyType.getDeclaredConstructor(ByteSequence.class).newInstance(key);
        } catch (InvocationTargetException ite) {
            switch (ite.getCause()) {
                case IllegalArgumentException iae:
                    throw iae;
                default:
                    throw new Error(ite);
            }
        } catch (Exception e) {
            throw new Error(e);
        }
    }

    static <T extends Key> Optional<T> fromEtcdKeyOptional(ByteSequence key, Class<T> keyType) {
        try {
            return Optional.of(fromEtcdKey(key, keyType));
        } catch (IllegalArgumentException iae) {
            return Optional.empty();
        }
    }

    default ByteSequence toEtcdKey() {
        return ByteSequence.from(toString(), StandardCharsets.UTF_8);
    }

    Optional<ByteSequence> toEtcdParentKey();

}
