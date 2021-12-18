/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import io.etcd.jetcd.ByteSequence;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record JobRosterKey(UUID jobId) implements Key {

  private static final Pattern PATTERN =
      Pattern.compile(
          "^/jobs/([0-9a-f]{8}\\b-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4" + "}-\\b[0-9a-f]{12})$");

  static UUID parse(ByteSequence etcdKey) {
    String etcdKeyStr = etcdKey.toString(StandardCharsets.UTF_8);
    Matcher m = PATTERN.matcher(etcdKeyStr);
    if (!m.matches())
      throw new IllegalArgumentException(String.format("'%s' is not a job roster key", etcdKeyStr));
    return UUID.fromString(m.group(1));
  }

  public JobRosterKey(ByteSequence etcdKey) {
    this(parse(etcdKey));
  }

  public JobRosterEntryKey getJobRosterEntryKey(int party) {
    return new JobRosterEntryKey(jobId, party);
  }

  @Override
  public String toString() {
    return String.format("/jobs/%s", jobId);
  }

  @Override
  public Optional<ByteSequence> toEtcdParentKey() {
    return Optional.of(new JobRosterDirectoryKey().toEtcdKey());
  }
}
