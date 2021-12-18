/*
 * Copyright (c) 2021 - for information on the respective copyright owner
 * see the NOTICE file and/or the repository https://github.com/carbynestack/klyshko.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package io.carbynestack.klyshko.provisioner;

import io.quarkus.logging.Log;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.QuarkusApplication;
import io.quarkus.runtime.annotations.QuarkusMain;
import javax.inject.Inject;

@QuarkusMain
public class KlyshkoProvisioner implements QuarkusApplication {

  public static void main(String... args) {
    Quarkus.run(KlyshkoProvisioner.class, args);
  }

  @Inject TupleUploader tupleUploader;

  @Override
  public int run(String... args) {
    Quarkus.waitForExit();
    boolean successful = tupleUploader.isSuccessful();
    if (!successful) {
      Log.errorf("Tuple upload failed - exiting with non-zero exit code");
    }
    return successful ? 0 : 1;
  }
}
