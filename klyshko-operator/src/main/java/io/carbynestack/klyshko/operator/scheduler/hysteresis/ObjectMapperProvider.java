/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.operator.scheduler.hysteresis;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.inject.Singleton;
import javax.ws.rs.Produces;

@Singleton
public class ObjectMapperProvider {

  @Produces
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }
}
