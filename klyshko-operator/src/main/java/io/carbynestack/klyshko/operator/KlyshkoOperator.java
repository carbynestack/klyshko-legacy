/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator;

import io.javaoperatorsdk.operator.Operator;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import javax.inject.Inject;

@QuarkusMain
public class KlyshkoOperator implements QuarkusApplication {

  public static final String ETCD_PREFIX = "klyshko";

  @Inject Operator operator;

  public static void main(String... args) {
    Quarkus.run(KlyshkoOperator.class, args);
  }

  @Override
  public int run(String... args) throws Exception {
    operator.start();
    Quarkus.waitForExit();
    return 0;
  }
}
