/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import io.etcd.jetcd.ByteSequence;
import io.vavr.Tuple2;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record JobRosterEntryKey(UUID jobId, int party) implements Key {

  private static final Pattern PATTERN =
      Pattern.compile(
          "^/jobs/([0-9a-f]{8}\\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4"
              + "}-\\b[0-9a-f]{12})/(\\d+)$");

  static Tuple2<UUID, Integer> parse(ByteSequence etcdKey) {
    String etcdKeyStr = etcdKey.toString(StandardCharsets.UTF_8);
    Matcher m = PATTERN.matcher(etcdKeyStr);
    if (!m.matches())
      throw new IllegalArgumentException(
          String.format("'%s' is not a job roster entry key", etcdKeyStr));
    return new Tuple2<>(UUID.fromString(m.group(1)), Integer.parseInt(m.group(2)));
  }

  public JobRosterEntryKey(ByteSequence etcdKey) {
    this(parse(etcdKey)._1, parse(etcdKey)._2);
  }

  public JobRosterKey getJobRosterKey() {
    return new JobRosterKey(jobId);
  }

  @Override
  public Optional<ByteSequence> toEtcdParentKey() {
    return Optional.of(getJobRosterKey().toEtcdKey());
  }

  @Override
  public String toString() {
    return String.format("%s/%s", getJobRosterKey(), party);
  }
}
